/*
 *  Copyright ErgoTech Systems, Inc 2014
 *
 * This file is made available online through a Creative Commons Attribution-ShareAlike 3.0  license.
 * (http://creativecommons.org/licenses/by-sa/3.0/)
 *
 *  This is a library of functions for the RPi to communicate with the BrickPi.
 */
package com.ergotech.brickpi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ergotech.brickpi.motion.Motor;
import com.ergotech.brickpi.motion.MotorPort;
import com.ergotech.brickpi.sensors.Sensor;
import com.ergotech.brickpi.sensors.SensorPort;
import com.ergotech.brickpi.sensors.SensorType;

/**
 * This class provides utility method for communication with the brick pi.
 */
public abstract class BrickPiCommunications {

    private static final Logger LOGGER = 
            LoggerFactory.getLogger(BrickPiCommunications.class.getName());

    /**
     * The current debug level.
     */
    public static int DEBUG_LEVEL = 0;
    
    /**
     * It would seem to be a desirable, and fairly likely feature that the brick
     * pis could be made stackable. In this case we will have multiple slaves on
     * the serial port. Currently this is not the case and we have only two, but
     * just to simplify future changes, I'll make this a constant.
     */
    public static final int SERIAL_TARGETS = 2;

    /**
     * Change the UART address.
     */
    public static final byte MSG_TYPE_CHANGE_ADDR = 1;
    /**
     * Change/set the sensor type.
     */
    public static final byte MSG_TYPE_SENSOR_TYPE = 2;
    /**
     * Set the motor speed and direction, and return the sesnors and encoders.
     */
    public static final byte MSG_TYPE_VALUES = 3;
    /**
     * Float motors immediately
     */
    public static final byte MSG_TYPE_E_STOP = 4;
    /**
     * Set the timeout
     */
    public static final byte MSG_TYPE_TIMEOUT_SETTINGS = 5;

    /** A thread safe list of event listeners .*/
    public final List<BrickPiUpdateListener> listeners;

    /**
     * The addresses of the 2 brick pi atmel chips. At this point in development
     * I have not yet found a reason why these should be exposed to the user at
     * all. If I find a reason, I'll expose them (maybe a future brick pi design
     * will need it).
     */
    protected final byte[] serialAddresses;

    /**
     * The array of sensors.
     */
    protected final Sensor[] sensorType;

    /**
     * The array of motors.
     */
    protected final Motor[] motors;

    /**
     * The executor that calls "updateValues" frequently. Tasks are scheduled
     * on this executor in "setupSensors".
     */
    protected final ScheduledExecutorService updateValuesExecutor = 
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Update values thread");
                    t.setDaemon(true);
                    return t;
                }
            });

    private volatile ScheduledFuture<?> scheduledPoller;

    /**
     * How frequently to call updateValues. This is in milliseconds. A value of
     * zero (or less) stops the updates. Actually, the thread waits this amount
     * of time after the previous call before calling again, so the update rate
     * will be slightly slower by the amount of time it take to complete the
     * call. Additionally, the thread may be woken up, eg by configuring a motor
     * to ensure that the value is passed to the BrickPi.
     */
    protected volatile int updateDelay;

    /**
     * Create the brick pi instance. This will only occur on the "getBrickPi"
     * call, and only if it has not already been created.
     *
     */
    protected BrickPiCommunications() {
        serialAddresses = new byte[SERIAL_TARGETS];
        serialAddresses[0] = 1;  // problem if SERIAL_TARGETS is not 2
        serialAddresses[1] = 2;
        sensorType = new Sensor[SERIAL_TARGETS * 2];
        motors = new Motor[SERIAL_TARGETS * 2];
        updateDelay = 100;
        listeners = Collections.synchronizedList(new ArrayList<BrickPiUpdateListener>());
    }

    /**
     * Send a packet to the brick pi.
     *
     * @param destinationAddress
     * @param packet
     */
    protected abstract void sendToBrickPi(byte destinationAddress, byte[] packet);

    /**
     * Read a packet from the brick pi.
     *
     * @param timeout total read timeout in ms
     * @return the packet read from the serial port/brickpi
     * @throws java.io.IOException thrown if there's a timeout reading the port.
     */
    protected abstract byte[] readFromBrickPi(int timeout) throws IOException;

    /**
     * Sets the motor timeout. This is a watchdog. If the brickpi has not seen a
     * message from the pi in this amount of time the motors will gracefully
     * halt.
     *
     * @param timeout the timeout in microseconds (us).
     * @throws java.io.IOException thrown if the message transaction fails.
     */
    public void setTimeout(long timeout) throws IOException {
        byte[] packet = new byte[] {
                MSG_TYPE_TIMEOUT_SETTINGS,
                (byte) (timeout & 0xFF),
                (byte) ((timeout >> 8) & 0xFF),
                (byte) ((timeout >> 16) & 0xFF),
                (byte) ((timeout >> 24) & 0xFF)
        };
        for (int counter = 0; counter < SERIAL_TARGETS; counter++) {
            serialTransactionWithRetry(counter, packet, 100);
        }
    }

    /**
     * Returns the current update delay.
     */
    public int getUpdateDelay() {
        return updateDelay;
    }

    /**
     * Sets the current update delay.
     */
    public void setUpdateDelay(int updateDelay) {
        this.updateDelay = updateDelay;
        if (updateDelay == 0 && null != scheduledPoller) {
            scheduledPoller.cancel(false);
        }
    }

    /**
     * Set the sensor at the particular port. There are current four sensor
     * ports.
     *
     * @param sensor the sensor to associate with the port. May be null to clear
     * the sensor configuration.
     * @param port the port.
     */
    public void setSensor(Sensor sensor, SensorPort port) {
        sensorType[port.getPort()] = sensor;
    }

    /**
     * Returns the sensor attached to a particular port. This method will not
     * return null. If a sensor has not previously been attached to the port, a
     * RawSensor will be created, attached and returned.
     *
     * @param <T> the sensor associated with the port
     * @param port the port associated with the requested sensor.
     * @return a valid Sensor object. If no sensor is current associated with
     * the port a RawSensor will be returned.
     */
    @SuppressWarnings("unchecked")
    public <T extends Sensor> T getSensor(SensorPort sensorPort) {
        int port = sensorPort.getPort();
        if (sensorType[port] == null) {
            LOGGER.debug("Uninitialized sensor: {}", port);
            sensorType[port] = new Sensor(SensorType.Raw);
        }
        return (T) sensorType[port];
    }

    /**
     * Set the motor at the particular port. There are currently four motor ports.
     *
     * @param motor the motor to associate with the port. May be null to clear
     * the motor configuration.
     * @param port the port. 
     */
    public void setMotor(Motor motor, MotorPort motorPort) {
        int port = motorPort.getPort();
        if (motors[port] != null) {
            // remove the association of this motor to the controller
            motors[port].setBrickPi(null);
        }
        motors[port] = motor;
        if (motors[port] != null) {
            // associate this motor with the controller
            motors[port].setBrickPi(this);
        }
    }

    /**
     * Returns the motor attached to a particular port. This method may return
     * null.
     *
     * @param port the port associated with the requested motor.
     * @return a valid motor object or null
     */
    public Motor getMotor(MotorPort port) {
        return motors[port.getPort()];
    }

    /**
     * Configure the sensors.
     *
     * @throws java.io.IOException thrown if no response is received from the
     * BrickPi
     */
    public boolean setupSensors() throws IOException {
        if (null != scheduledPoller) {
            scheduledPoller.cancel(false);
        }
        
        for (int counter = 0; counter < SERIAL_TARGETS; counter++) {
            int startingBitLocation = 0;
            byte[] packet;
            // we're going to use a BitSet to pack the bits.
            BitSet sensorData = new BitSet();
            for (int sensorCount = 0; sensorCount < 2; sensorCount++) {
                Sensor currentSensor = sensorType[counter * 2 + sensorCount];
                if (currentSensor != null) {
                    // request that each sensor encode itself into the packet.
                    currentSensor.encodeToSetup(sensorData, startingBitLocation);
                }
            }
            byte[] sensorBytes = sensorData.toByteArray();
            // create a packet of the correct size and fill in the header data.
            packet = new byte[sensorBytes.length + 3];
            System.arraycopy(sensorBytes, 0, packet, 3, sensorBytes.length);
            packet[0] = MSG_TYPE_SENSOR_TYPE;
            // fill in bytes 1 & 2 the sensor types. Counter is still the serial target
            // sensor count is 1 or 2 so the second or third byte in the message.
            for (int sensorCount = 0; sensorCount < 2; sensorCount++) {
                if (sensorType[counter * 2 + sensorCount] == null) {
                    packet[1 + sensorCount] = 0;
                } else {
                    packet[1 + sensorCount] = sensorType[counter * 2 + sensorCount].getSensorType();
                }
            }
            serialTransactionWithRetry(counter, packet, 2500);
            // should probably check the response here...
        }
	//Tom - interesting, we spin up a thread for each BP
	//      stored in scheduledPoller
        // set up the polling thread
        if (updateDelay > 0) {
            Runnable update = new Runnable() {

                @Override
                public void run() {
                    try {
                        final BrickPiUpdateListener[] listenerArray = listeners.toArray(new BrickPiUpdateListener[0]);
                        updateValues();
                        // notify listeners.
                        for ( BrickPiUpdateListener listener : listenerArray ) {
                            listener.updateReceived(BrickPiCommunications.this);
                        }
                    } catch (Exception any) {
                        LOGGER.error(any.getMessage(), any);
                    }
                }

            };
            scheduledPoller = updateValuesExecutor.scheduleAtFixedRate(update, updateDelay, updateDelay, TimeUnit.MILLISECONDS);
            return true;
        }

        return false;
    }

    /**
     * Poll the BrickPi for new values.
     */
    public void updateValues() throws IOException {
        for (int counter = 0; counter < SERIAL_TARGETS; counter++) {
            int startingBitLocation = 0;
            
            // we're going to use a BitSet to pack the bits.
            BitSet pollingData = new BitSet();
            // encoder offsets are not supported.  This code will need to be changed
            // when they are.
            // When there are no encoder offsets, the first two bits of the 
            // bitset need to be zeroed.
            pollingData.clear(0, 2);
            startingBitLocation += 2;  // account for these bits.
	    //tom - traverse all the motors, ask them to encode their bytes into
	    //the pollingdata at 10-bit offsets
	    
            for (int motorCount = 0; motorCount < 2; motorCount++) {
		//tom - this looks like an obfuscated way to address our motors
		//while maintaining indices....ugh...indexing overhead :(
                Motor motor = motors[counter * 2 + motorCount];
                if (motor != null) {
                    // request that each motor encode itself into the packet.
                    startingBitLocation = motor.encodeToValueRequest(pollingData, startingBitLocation);
                } else {
                    // we have to encode 10 bits of zero.
                    pollingData.clear(startingBitLocation, startingBitLocation + 10);
                    startingBitLocation += 10;
                }
            }
	    // Tom - why do we only count up to sensorCount of 2? because we're using it
	    // for janky indexing bs.
	    // For most of these, we don't appear to be encoding anything...
            for (int sensorCount = 0; sensorCount < 2; sensorCount++) {
                Sensor currentSensor = sensorType[counter * 2 + sensorCount];
                if (currentSensor != null) {
                    // request that each sensor encode itself into the packet.
		    // tom - note , the default here is to do nothing, returns the next place
		    // to write bits to.
                    startingBitLocation = currentSensor.encodeToValueRequest(pollingData, startingBitLocation);
                }
            }
            byte[] pollingBytes = pollingData.toByteArray();
            if ((startingBitLocation % 8) == 0) {
                pollingBytes = Arrays.copyOf(pollingBytes, startingBitLocation / 8);
            } else {
                pollingBytes = Arrays.copyOf(pollingBytes, startingBitLocation / 8 + 1);
            }
            // create a packet of the correct size and fill in the header data.
            byte[] packet = new byte[pollingBytes.length + 1];
            System.arraycopy(pollingBytes, 0, packet, 1, pollingBytes.length);
            packet[0] = MSG_TYPE_VALUES;
            byte[] values = serialTransactionWithRetry(counter, packet, 50);
//            if (values != null) {
//                 
//                values = new byte[]{(byte) 0x3 , (byte) 0x74 , (byte) 0x10 , (byte) 0xc , (byte) 0x20 , (byte) 0xfd , (byte) 0xf7 , (byte) 0x1f};
//            Gives: BrickPi.Temp_BitsUsed[0]: 20 BrickPi.Temp_BitsUsed[1]: 3
//            Gives:  Temp_EncoderVal: 525060 [0]
//            Gives:  Temp_EncoderVal: 4 [1]
//            }
            if (values != null && values[0] == MSG_TYPE_VALUES) { // hard to think it would be anything else
                //BitSet incoming = BitSet.valueOf(values);
                startingBitLocation = 8; // the message type is still in there, so forget that
                // there are 5 bits associated with each of the encoders
                // these are then encode word length.
                int bitLength = 5;
                // I think that the motor decode method could/should be used to decode these values
                // problem is that it's encoder0 length, encoder1 length, encoder0 value, encoder1 value
                // rather than dealing with each encoder as a block.
                int encoderWordLength0 = decodeInt(bitLength, values, startingBitLocation);
                startingBitLocation += 5;  // skip encoder lengths
                int encoderWordLength1 = decodeInt(bitLength, values, startingBitLocation);
                startingBitLocation += 5;  // skip encoder lengths
                Motor motor = motors[counter * 2];
                if (motor != null) {
                    motor.decodeValues(encoderWordLength0, values, startingBitLocation);
                }
                //int encoderVal0 = decodeInt(encoderWordLength0, incoming, startingBitLocation);
                startingBitLocation += encoderWordLength0;
                motor = motors[counter * 2 + 1];
                if (motor != null) {
                    motor.decodeValues(encoderWordLength1, values, startingBitLocation);
                }
                try {
                    //int encoderVal1 = decodeInt(encoderWordLength1, incoming, startingBitLocation);
                    startingBitLocation += encoderWordLength1;
                    for (int sensorCount = 0; sensorCount < 2; sensorCount++) {
                        Sensor currentSensor = sensorType[counter * 2 + sensorCount];
                        if (currentSensor != null) {
                            // request that each sensor encode itself into the packet.
                            startingBitLocation = currentSensor.decodeValues(values, startingBitLocation);
                        } else {
                            startingBitLocation += 10;  // the default seems to be 10 bits....
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        }

    }

    /**
     * Decode an arbitrary number of bits from the bitset.
     *
     * @param bitLength the number of bits to decode
     * @param incoming the bitset to decode them from
     * @param startingBitLocation the starting bit location in the bitset
     * @return the decoded value
     */
    public static int decodeInt(int bitLength, byte[] incoming, int startingBitLocation) {
        int value = 0;
        while (bitLength-- > 0) {
            value <<= 1;
            int location = bitLength + startingBitLocation;
            boolean set = ((incoming[location / 8] & (1 << (location % 8))) != 0);
            if (set) {
                value |= 1;
            }
        }
        return value;
    }

    /**
     * Send the packet to the BrickPi. This will retry up to five times. This
     * method is synchronized to prevent multiple threads from attempting to use
     * the serial interface simultaneously.
     *
     * @param addressPointer the index into the serialAddresses array
     * @param packet the packet to send
     * @param timeout how long to wait for a reply
     * @return the received packet
     * @throws IOException throw if anything goes wrong.
     */
    protected synchronized byte[] serialTransactionWithRetry(int addressPointer, byte[] packet, int timeout) throws IOException {
        byte[] response;
        // this is ridiculous.  The serial interface should be, basically 100% reliable
        // it's inexcusable to have to add retry-hacks.
        // the "for" loop will exit on success and return
        // and if that doesn't happen then the method will throw.
        IOException lastioe = null;
        for (int retry = 0; retry < 5; retry++) {
            try {
                sendToBrickPi(serialAddresses[addressPointer], packet);
                response = readFromBrickPi(timeout);
                return response; // if we were successful break out here.
            } catch (IOException ioe) {
                lastioe = ioe;
            }
        }
        throw lastioe;
    }
    
    /** Add a listener for update events.  This method only allows the same listener
     * to be added once.
     * @param listener a listener for update events.
     */ 
    public void addBrickPiUpdateListener(BrickPiUpdateListener listener) {
        if ( !listeners.contains(listener) ) {
            listeners.add(listener);
        }
    }

    /** Remove a listener for update events. 
     * @param listener a listener for update events.
     */ 
    public void removeBrickPiUpdateListener(BrickPiUpdateListener listener) {
        while ( listeners.contains(listener) ) {
            listeners.remove(listener);
        }
    }

}

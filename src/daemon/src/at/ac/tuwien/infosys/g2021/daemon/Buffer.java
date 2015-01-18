package at.ac.tuwien.infosys.g2021.daemon;

import at.ac.tuwien.infosys.g2021.common.BufferClass;
import at.ac.tuwien.infosys.g2021.common.BufferConfiguration;
import at.ac.tuwien.infosys.g2021.common.BufferState;
import at.ac.tuwien.infosys.g2021.common.SimpleData;
import at.ac.tuwien.infosys.g2021.common.util.NotYetImplementedError;
import java.util.Date;
import java.util.Map;

/**
 * A buffer contains the current value of single a gatherer converted meaningful value and the
 * according metadata, which explains the meaning of the buffer value. The translation between
 * hardware values from a gatherer and the buffer values is done using adapters. Sensor values
 * from the gatherer are put into a chain of adapters and the result is stored as buffer value.
 * A current actor value is the last value put into the buffer. This value is converted by the
 * adapters and then put into the gatherer.
 */
class Buffer extends ValueChangeProducer {

    // This inner class listens for value changes of the gatherer
    private class GathererListener implements ValueChangeConsumer {

        /**
         * This is the notification of a spontaneous value change.
         *
         * @param newValue the new buffer value
         */
        @Override
        public void valueChanged(SimpleData newValue) { gathererValueChanged(newValue); }
    }

    // This inner class listens for value changes of the gatherer
    private class AdapterListener implements ValueChangeConsumer {

        /**
         * This is the notification of a spontaneous value change.
         *
         * @param newValue the new buffer value
         */
        @Override
        public void valueChanged(SimpleData newValue) { adapterValueChanged(newValue); }
    }

    // The buffer name
    private String name;

    // The configuration
    private BufferConfiguration configuration;

    // The adapter chain
    private AdapterChain adapters;
    private AdapterListener adapterListener;

    // The used gatherer
    private Gatherer gatherer;
    private GathererListener gathererListener;

    // The current buffer value
    private SimpleData currentValue;

    // And another object for thread synchronization
    private final Object lock;

    /** Initializing without configuration. */
    private Buffer() {

        lock = new Object();
    }

    /**
     * Initialization of the buffer.
     *
     * @param name   the buffer name
     * @param config the buffer configuration
     *
     * @throws IllegalArgumentException if there are inconsistencies within the configuration
     */
    Buffer(String name, BufferConfiguration config) throws IllegalArgumentException {

        this();
        this.name = name;
        this.configuration = config;

        // Looking for the gatherer
        gatherer = Daemon.get().gatherers().gathererForConfiguration(config.getGatherer());
        if (config.getBufferClass() == BufferClass.ACTOR && !gatherer.canUseAsActor()) {
            shutdown();
            throw new IllegalArgumentException("gatherer cannot be used as actor");
        }
        gathererListener = new GathererListener();
        gatherer.addValueChangeConsumer(gathererListener);

        // Installing the adapter chain
        adapters = new AdapterChain(config);
        adapterListener = new AdapterListener();
        adapters.addValueChangeConsumer(adapterListener);

        // Now the parts are playing together. But they must be initialized.
        if (config.getBufferClass() == BufferClass.ACTOR) {
            // There is no value put to the gatherer. If the gatherer is ready, we stay in
            // the state INITIALIZING. Otherwise we take the gatherer state.
            takeStateFromGatherer(BufferState.INITIALIZING);
        }
        else if (config.getBufferClass() == BufferClass.SENSOR) {
            // For a sensor, we just put the current hardware value into the adapter chain.
            // Then anything goes the right way.
            adapters.put(gatherer.get());
        }
        else {
            shutdown();
            throw new NotYetImplementedError("unexpected buffer class: " + config.getBufferClass().name());
        }
    }

    /** Releases any system resources of the gatherer. */
    void shutdown() {

        synchronized (lock) {

            // release the adapters
            if (adapters != null) {
                adapters.shutdown();
                adapters = null;
                adapterListener = null;
            }

            // release the gatherer
            if (gatherer != null) {
                gatherer.removeValueChangeConsumer(gathererListener);
                if (!gatherer.hasValueChangeConsumers()) Daemon.get().gatherers().remove(gatherer);
                gatherer = null;
                gathererListener = null;
            }

            // Now the buffer is released
            fireValueChange(new SimpleData(name, new Date(), BufferState.RELEASED));
            super.shutdown();
        }
    }

    /**
     * This is the notification of a spontaneous value change of the installed adapters.
     * In case of sensors, this value is the new buffer value. In case of actors, this value
     * must be put into the gatherer.
     *
     * @param newValue the new buffer value
     */
    private void adapterValueChanged(SimpleData newValue) {

        synchronized (lock) {
            if (configuration.getBufferClass() == BufferClass.SENSOR) {
                setValue(newValue);
            }
            else if (configuration.getBufferClass() == BufferClass.ACTOR && gatherer != null) {
                if (!gatherer.set(newValue.getValue())) takeStateFromGatherer(BufferState.FAULTED);
            }
        }
    }

    /**
     * This is the notification of a spontaneous value change of the gatherer. In case of sensors this value
     * must put into the adapter chain to become the current value. For actors only state changes are accepted.
     * Value changes of actors are ignored.
     *
     * @param newValue the new buffer value
     */
    private void gathererValueChanged(SimpleData newValue) {

        synchronized (lock) {
            if (gatherer != null) {
                if (configuration.getBufferClass() == BufferClass.SENSOR && adapters != null) {
                    adapters.put(gatherer.get());
                }
                else if (configuration.getBufferClass() == BufferClass.ACTOR && newValue.getState() != BufferState.READY) {
                    takeStateFromGatherer(BufferState.FAULTED);
                }
            }
        }
    }

    /**
     * Returns the name of this buffer.
     *
     * @return the buffer name
     */
    String getName() { return name; }

    /**
     * Returns the current buffer configuration.
     *
     * @return the buffer configuration
     */
    BufferConfiguration getConfiguration() { return configuration; }

    /**
     * Returns the current buffer metainfo.
     *
     * @return the buffer metainfo
     */
    Map<String, String> getMetainfo() { return configuration.getMetainfo(); }

    /**
     * Lets the state of the gatherer become the current state.
     *
     * @param readyState the state, which should replace the ready state of the gatherer
     */
    private void takeStateFromGatherer(BufferState readyState) {

        synchronized (lock) {

            BufferState state = gatherer == null ? BufferState.RELEASED : gatherer.get().getState();

            if (state == BufferState.READY) state = readyState;
            setValue(new SimpleData(name, new Date(), state));
        }
    }

    /**
     * This sets the new value and distributes the value change.
     *
     * @param newValue the new buffer value
     */
    private void setValue(SimpleData newValue) {

        synchronized (lock) {
            if (currentValue == null
                || currentValue.getState() != newValue.getState()
                || newValue.getState() == BufferState.READY && !newValue.getValue().equals(currentValue.getValue())) {

                currentValue = newValue;
                fireValueChange(currentValue);
            }
        }
    }

    /**
     * Returns the current buffer value.
     *
     * @return the current buffer value
     */
    SimpleData get() {

        synchronized (lock) {
            return currentValue;
        }
    }

    /**
     * Puts the current value into the gatherer of an actor.
     *
     * @param value the new buffer value
     *
     * @return <tt>true</tt>, if the gatherer is an actor and is in the ready state.
     */
    boolean put(Number value) {

        if (configuration.getBufferClass() == BufferClass.ACTOR) {

            synchronized (lock) {

                BufferState state = gatherer == null ? BufferState.RELEASED : gatherer.get().getState();

                if (state == BufferState.READY) {

                    SimpleData data = new SimpleData(name, new Date(), state, value);

                    setValue(data);
                    adapters.put(data);
                    return true;
                }
            }
        }

        return false;
    }
}


package cr.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class RegisterBank {
    public static final int REGISTER_COUNT = 3;

    public static final int POWER = 0;
    public static final int TEMPERATURE_C = 1;
    public static final int SLEEP_TIMER_MINUTES = 2;

    public static final int STATUS_POWER = 0;
    public static final int STATUS_TEMPERATURE_TENTHS_C = 1;
    public static final int STATUS_SLEEP_TIMER_MINUTES = 2;

    public static final int CONTROL_COUNT = 14;
    public static final int CONTROL_POWER = 0;
    public static final int CONTROL_MODE = 1;
    public static final int CONTROL_FAN = 2;
    public static final int CONTROL_SETPOINT_C = 3;
    public static final int CONTROL_TURBO = 4;
    public static final int CONTROL_QUIET = 5;
    public static final int CONTROL_SWEEP_LR = 6;
    public static final int CONTROL_SWEEP_UD = 7;
    public static final int CONTROL_DISPLAY = 8;
    public static final int CONTROL_IONIZER = 9;
    public static final int CONTROL_AUX_HEATER = 10;
    public static final int CONTROL_SLEEP = 11;
    public static final int CONTROL_ENERGY_SAVING = 12;
    public static final int CONTROL_TIMER_MINUTES = 13;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int[] holding = new int[REGISTER_COUNT];
    private final int[] input = new int[REGISTER_COUNT];
    private final int[] controls = new int[CONTROL_COUNT];
    private final AtomicBoolean controlsDirty = new AtomicBoolean();
    private boolean controlsInitialized;
    private Instant lastValidFrame;
    private long validFrames;
    private long crcErrors;
    private final Duration staleAfter;

    public RegisterBank() {
        this(Duration.ofSeconds(30));
    }

    public RegisterBank(Duration staleAfter) {
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        this.staleAfter = staleAfter;
        holding[POWER] = 0;
        holding[TEMPERATURE_C] = 24;
        holding[SLEEP_TIMER_MINUTES] = 0;
        controls[CONTROL_POWER] = 0;
        controls[CONTROL_MODE] = 0;
        controls[CONTROL_FAN] = 0;
        controls[CONTROL_SETPOINT_C] = 24;
    }

    public int[] readHolding(int address, int count) {
        return slice(holding, address, count);
    }

    public int[] readInput(int address, int count) {
        return slice(input, address, count);
    }

    public void writeHolding(int address, int[] values) {
        validateRange(address, values.length);
        lock.writeLock().lock();
        try {
            if (!controlsInitialized) {
                throw new IllegalStateException("controls are unavailable until the first valid A3 state frame");
            }
            int[] candidate = Arrays.copyOf(holding, holding.length);
            System.arraycopy(values, 0, candidate, address, values.length);
            validatePublicControls(candidate);
            System.arraycopy(values, 0, holding, address, values.length);
            controls[CONTROL_POWER] = holding[POWER];
            controls[CONTROL_SETPOINT_C] = holding[TEMPERATURE_C];
            controls[CONTROL_SLEEP] = holding[SLEEP_TIMER_MINUTES] == 0 ? 0 : 1;
            controls[CONTROL_TIMER_MINUTES] = holding[SLEEP_TIMER_MINUTES];
            controlsDirty.set(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int[] controlsSnapshot() {
        lock.readLock().lock();
        try {
            return Arrays.copyOf(controls, controls.length);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean consumeControlsDirty() {
        return controlsDirty.getAndSet(false);
    }

    public void markControlsDirty() {
        controlsDirty.set(true);
    }

    public void updateFromA3(byte[] frame) {
        lock.writeLock().lock();
        try {
            int packedMode = u(frame[13]);
            int packedSetpoint = u(frame[14]);
            int power = bit(packedMode, 3);
            int timerMinutes = (u(frame[19]) << 8) | u(frame[20]);

            input[STATUS_POWER] = power;
            input[STATUS_TEMPERATURE_TENTHS_C] = u(frame[10]) * 10 + u(frame[11]);
            input[STATUS_SLEEP_TIMER_MINUTES] = timerMinutes;
            lastValidFrame = Instant.now();
            validFrames++;

            if (!controlsInitialized) {
                controlsInitialized = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateRemoteState(int value) {
        lock.writeLock().lock();
        try {
            lastValidFrame = Instant.now();
            validFrames++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void recordCrcError() {
        lock.writeLock().lock();
        try {
            crcErrors++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Instant lastValidFrame() {
        lock.readLock().lock();
        try {
            return lastValidFrame;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isOnline() {
        return lastValidFrameAgeSeconds() <= staleAfter.toSeconds();
    }

    public int lastValidFrameAgeSeconds() {
        lock.readLock().lock();
        try {
            long age = lastValidFrame == null ? 0xFFFFL
                    : Math.max(0, Duration.between(lastValidFrame, Instant.now()).toSeconds());
            return (int) Math.min(age, 0xFFFF);
        } finally {
            lock.readLock().unlock();
        }
    }

    private int[] slice(int[] source, int address, int count) {
        lock.readLock().lock();
        try {
            validateRange(address, count);
            return Arrays.copyOfRange(source, address, address + count);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void validateRange(int address, int count) {
        if (address < 0 || count < 1 || address + count > REGISTER_COUNT) {
            throw new IllegalArgumentException("register range is outside 0.." + (REGISTER_COUNT - 1));
        }
    }

    private static void validatePublicControls(int[] values) {
        range(values[POWER], 0, 1, "power");
        range(values[TEMPERATURE_C], 16, 31, "temperature");
        range(values[SLEEP_TIMER_MINUTES], 0, 0xFFFF, "sleep timer");
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be " + min + ".." + max);
        }
    }

    private static int u(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static int bit(int value, int bit) {
        return (value >>> bit) & 1;
    }
}

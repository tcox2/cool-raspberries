package cr.core;

import java.time.Instant;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class RegisterBank {
    public static final int REGISTER_COUNT = 32;
    public static final int WRITABLE_CONTROL_COUNT = 14;

    public static final int POWER = 0;
    public static final int MODE = 1;
    public static final int FAN = 2;
    public static final int SETPOINT_C = 3;
    public static final int TURBO = 4;
    public static final int QUIET = 5;
    public static final int SWEEP_LR = 6;
    public static final int SWEEP_UD = 7;
    public static final int DISPLAY = 8;
    public static final int IONIZER = 9;
    public static final int AUX_HEATER = 10;
    public static final int SLEEP = 11;
    public static final int ENERGY_SAVING = 12;
    public static final int TIMER_MINUTES = 13;

    public static final int STATUS_RETURN_AIR_TENTHS_C = 0;
    public static final int STATUS_POWER = 1;
    public static final int STATUS_MODE = 2;
    public static final int STATUS_FAN = 3;
    public static final int STATUS_SETPOINT_C = 4;
    public static final int STATUS_FLAGS = 5;
    public static final int STATUS_SWEEP_LR = 6;
    public static final int STATUS_SWEEP_UD = 7;
    public static final int STATUS_TIMER_MINUTES = 8;
    public static final int STATUS_OPERATING_HOURS = 9;
    public static final int STATUS_REQUESTED_MODE = 10;
    public static final int STATUS_REMOTE_STATE = 11;
    public static final int STATUS_AC_ONLINE = 12;
    public static final int STATUS_LAST_FRAME_AGE_SECONDS = 13;
    public static final int STATUS_VALID_FRAMES_LOW = 14;
    public static final int STATUS_CRC_ERRORS_LOW = 15;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int[] holding = new int[REGISTER_COUNT];
    private final int[] input = new int[REGISTER_COUNT];
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
    }

    public int[] readHolding(int address, int count) {
        return slice(holding, address, count);
    }

    public int[] readInput(int address, int count) {
        lock.writeLock().lock();
        try {
            updateHealth();
            return checkedSlice(input, address, count);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void writeHolding(int address, int[] values) {
        validateRange(address, values.length);
        if (address + values.length > WRITABLE_CONTROL_COUNT) {
            throw new IllegalArgumentException("holding registers 14..31 are reserved");
        }
        int[] candidate;
        lock.writeLock().lock();
        try {
            if (!controlsInitialized) {
                throw new IllegalStateException("controls are unavailable until the first valid A3 state frame");
            }
            candidate = Arrays.copyOf(holding, holding.length);
            System.arraycopy(values, 0, candidate, address, values.length);
            validateControls(candidate);
            System.arraycopy(values, 0, holding, address, values.length);
            controlsDirty.set(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int[] controlsSnapshot() {
        return readHolding(0, REGISTER_COUNT);
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
            int sweep = u(frame[15]);
            int config = u(frame[16]);
            input[STATUS_RETURN_AIR_TENTHS_C] = u(frame[10]) * 10 + u(frame[11]);
            input[STATUS_POWER] = bit(packedMode, 3);
            input[STATUS_MODE] = packedMode & 0x07;
            input[STATUS_FAN] = (packedMode >>> 4) & 0x07;
            input[STATUS_SETPOINT_C] = (packedSetpoint & 0x0F) + 16;
            input[STATUS_FLAGS] = (bit(packedMode, 7) << 0)
                    | (bit(packedSetpoint, 6) << 1)
                    | (bit(config, 7) << 2)
                    | (bit(config, 6) << 3)
                    | (bit(config, 4) << 4)
                    | (bit(config, 1) << 5)
                    | (bit(config, 0) << 6)
                    | (bit(u(frame[12]), 0) << 7)
                    | (bit(u(frame[12]), 1) << 8)
                    | (bit(u(frame[12]), 2) << 9);
            input[STATUS_SWEEP_LR] = (sweep >>> 4) & 0x0F;
            input[STATUS_SWEEP_UD] = sweep & 0x0F;
            input[STATUS_TIMER_MINUTES] = (u(frame[19]) << 8) | u(frame[20]);
            input[STATUS_OPERATING_HOURS] = (u(frame[23]) << 8) | u(frame[24]);
            input[STATUS_REQUESTED_MODE] = u(frame[21]) & 0x07;
            lastValidFrame = Instant.now();
            validFrames++;
            if (!controlsInitialized) {
                holding[POWER] = input[STATUS_POWER];
                holding[MODE] = input[STATUS_MODE];
                holding[FAN] = input[STATUS_FAN];
                holding[SETPOINT_C] = input[STATUS_SETPOINT_C];
                holding[TURBO] = bit(packedMode, 7);
                holding[QUIET] = bit(packedSetpoint, 6);
                holding[SWEEP_LR] = input[STATUS_SWEEP_LR];
                holding[SWEEP_UD] = input[STATUS_SWEEP_UD];
                holding[DISPLAY] = bit(config, 7);
                holding[IONIZER] = bit(config, 6);
                holding[AUX_HEATER] = bit(config, 4);
                holding[SLEEP] = bit(config, 1);
                holding[ENERGY_SAVING] = bit(config, 0);
                holding[TIMER_MINUTES] = input[STATUS_TIMER_MINUTES];
                controlsInitialized = true;
            }
            updateHealth();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateRemoteState(int value) {
        lock.writeLock().lock();
        try {
            input[STATUS_REMOTE_STATE] = value;
            lastValidFrame = Instant.now();
            validFrames++;
            updateHealth();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void recordCrcError() {
        lock.writeLock().lock();
        try {
            crcErrors++;
            input[STATUS_CRC_ERRORS_LOW] = (int) crcErrors & 0xFFFF;
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

    private int[] slice(int[] source, int address, int count) {
        lock.readLock().lock();
        try {
            return checkedSlice(source, address, count);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static int[] checkedSlice(int[] source, int address, int count) {
        validateRange(address, count);
        return Arrays.copyOfRange(source, address, address + count);
    }

    private static void validateRange(int address, int count) {
        if (address < 0 || count < 1 || address + count > REGISTER_COUNT) {
            throw new IllegalArgumentException("register range is outside 0.." + (REGISTER_COUNT - 1));
        }
    }

    private static void validateControls(int[] c) {
        checkBoolean(c[POWER], "power");
        range(c[MODE], 0, 4, "mode");
        range(c[FAN], 0, 7, "fan");
        range(c[SETPOINT_C], 16, 31, "setpoint");
        checkBoolean(c[TURBO], "turbo");
        checkBoolean(c[QUIET], "quiet");
        range(c[SWEEP_LR], 0, 15, "sweep LR");
        range(c[SWEEP_UD], 0, 15, "sweep UD");
        checkBoolean(c[DISPLAY], "display");
        checkBoolean(c[IONIZER], "ionizer");
        checkBoolean(c[AUX_HEATER], "aux heater");
        checkBoolean(c[SLEEP], "sleep");
        checkBoolean(c[ENERGY_SAVING], "energy saving");
        range(c[TIMER_MINUTES], 0, 0xFFFF, "timer");
    }

    private static void checkBoolean(int value, String name) {
        range(value, 0, 1, name);
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max);
    }

    private void updateHealth() {
        long age = lastValidFrame == null ? 0xFFFFL
                : Math.max(0, DurationSeconds.between(lastValidFrame, Instant.now()));
        input[STATUS_LAST_FRAME_AGE_SECONDS] = (int) Math.min(age, 0xFFFF);
        input[STATUS_AC_ONLINE] = age <= staleAfter.toSeconds() ? 1 : 0;
        input[STATUS_VALID_FRAMES_LOW] = (int) validFrames & 0xFFFF;
        input[STATUS_CRC_ERRORS_LOW] = (int) crcErrors & 0xFFFF;
    }

    private static int u(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static int bit(int value, int bit) {
        return (value >>> bit) & 1;
    }

    private static final class DurationSeconds {
        static long between(Instant from, Instant to) {
            return java.time.Duration.between(from, to).toSeconds();
        }
    }
}

package cr.core;

import org.junit.jupiter.api.Test;
import cr.TestFrames;

import static org.junit.jupiter.api.Assertions.*;

class RegisterBankTest {
    @Test
    void rejectsControlsBeforeBaselineStateAndReservedWrites() {
        RegisterBank bank = new RegisterBank();
        assertThrows(IllegalStateException.class,
                () -> bank.writeHolding(RegisterBank.POWER, new int[]{1}));
        bank.updateFromA3(TestFrames.sampleA3());
        assertThrows(IllegalArgumentException.class,
                () -> bank.writeHolding(14, new int[]{1}));
    }

    @Test
    void validatesControlValuesAtomically() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        bank.writeHolding(RegisterBank.SETPOINT_C, new int[]{22});
        assertEquals(22, bank.readHolding(RegisterBank.SETPOINT_C, 1)[0]);
        int modeBefore = bank.readHolding(RegisterBank.MODE, 1)[0];
        int fanBefore = bank.readHolding(RegisterBank.FAN, 1)[0];

        assertThrows(IllegalArgumentException.class,
                () -> bank.writeHolding(RegisterBank.MODE, new int[]{9, 25}));
        assertEquals(modeBefore, bank.readHolding(RegisterBank.MODE, 1)[0]);
        assertEquals(fanBefore, bank.readHolding(RegisterBank.FAN, 1)[0]);
        assertEquals(22, bank.readHolding(RegisterBank.SETPOINT_C, 1)[0]);
    }

    @Test
    void marksControlWritesDirty() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        assertFalse(bank.consumeControlsDirty());
        bank.writeHolding(RegisterBank.POWER, new int[]{1});
        assertTrue(bank.consumeControlsDirty());
        assertFalse(bank.consumeControlsDirty());
    }
}

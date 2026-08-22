package cr.core;

import org.junit.jupiter.api.Test;
import cr.TestFrames;

import static org.junit.jupiter.api.Assertions.*;

class RegisterBankTest {
    @Test
    void usesDefaultsInsteadOfTheFirstObservedState() {
        RegisterBank bank = new RegisterBank();
        assertThrows(IllegalStateException.class,
                () -> bank.writeHolding(RegisterBank.POWER, new int[]{1}));
        bank.updateFromA3(TestFrames.sampleA3());
        assertArrayEquals(new int[]{0, 24, 0}, bank.readHolding(0, 3));
        int[] controls = bank.controlsSnapshot();
        assertEquals(0, controls[RegisterBank.CONTROL_MODE]);
        assertEquals(0, controls[RegisterBank.CONTROL_FAN]);
        assertEquals(0, controls[RegisterBank.CONTROL_TURBO]);
        assertThrows(IllegalArgumentException.class,
                () -> bank.writeHolding(3, new int[]{1}));
    }

    @Test
    void validatesControlValuesAtomically() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        bank.writeHolding(RegisterBank.TEMPERATURE_C, new int[]{22});
        assertEquals(22, bank.readHolding(RegisterBank.TEMPERATURE_C, 1)[0]);

        assertThrows(IllegalArgumentException.class,
                () -> bank.writeHolding(RegisterBank.POWER, new int[]{1, 40, 30}));
        assertArrayEquals(new int[]{0, 22, 0}, bank.readHolding(0, 3));
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

    @Test
    void reportsAirConditionerProtocolDiagnostics() {
        RegisterBank bank = new RegisterBank();
        bank.recordAcRequest();
        bank.recordAcRequest();
        bank.recordAcResponse();
        bank.recordCrcError();
        bank.updateFromA3(TestFrames.sampleA3());

        RegisterBank.AcDiagnostics diagnostics = bank.acDiagnostics();
        assertEquals(2, diagnostics.requests());
        assertEquals(1, diagnostics.responses());
        assertEquals(1, diagnostics.crcErrors());
        assertEquals(1, diagnostics.validStateFrames());
        assertEquals(0, diagnostics.lastValidFrameAgeSeconds());
    }
}

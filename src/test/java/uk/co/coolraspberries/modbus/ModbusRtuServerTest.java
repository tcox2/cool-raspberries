package uk.co.coolraspberries.modbus;

import org.junit.jupiter.api.Test;
import uk.co.coolraspberries.TestFrames;
import uk.co.coolraspberries.core.RegisterBank;
import uk.co.coolraspberries.protocol.Crc16;

import static org.junit.jupiter.api.Assertions.*;

class ModbusRtuServerTest {
    @Test
    void writesAndReadsHoldingRegister() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        ModbusRtuServer server = new ModbusRtuServer(null, bank);

        byte[] write = Crc16.appendModbusCrc(new byte[]{1, 6, 0, 3, 0, 22});
        assertArrayEquals(write, server.handle(write));
        assertEquals(22, bank.readHolding(3, 1)[0]);

        byte[] read = Crc16.appendModbusCrc(new byte[]{1, 3, 0, 3, 0, 1});
        byte[] response = server.handle(read);
        assertArrayEquals(Crc16.appendModbusCrc(new byte[]{1, 3, 2, 0, 22}), response);
        assertTrue(Crc16.validModbusFrame(response));
    }

    @Test
    void returnsExceptionForOutOfRangeWrite() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        ModbusRtuServer server = new ModbusRtuServer(null, bank);
        byte[] write = Crc16.appendModbusCrc(new byte[]{1, 6, 0, 3, 0, 40});
        byte[] response = server.handle(write);
        assertArrayEquals(Crc16.appendModbusCrc(new byte[]{1, (byte) 0x86, 3}), response);
    }
}

package cr.modbus;

import org.junit.jupiter.api.Test;
import cr.TestFrames;
import cr.core.RegisterBank;
import cr.protocol.Crc16;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;

class ModbusRtuServerTest {
    @Test
    void writesAndReadsHoldingRegister() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        ModbusRtuServer server = new ModbusRtuServer(null, Map.of(1, bank));

        byte[] write = Crc16.appendModbusCrc(new byte[]{1, 6, 0, 1, 0, 22});
        assertArrayEquals(write, server.handle(write));
        assertEquals(22, bank.readHolding(1, 1)[0]);

        byte[] read = Crc16.appendModbusCrc(new byte[]{1, 3, 0, 1, 0, 1});
        byte[] response = server.handle(read);
        assertArrayEquals(Crc16.appendModbusCrc(new byte[]{1, 3, 2, 0, 22}), response);
        assertTrue(Crc16.validModbusFrame(response));
    }

    @Test
    void returnsExceptionForOutOfRangeWrite() {
        RegisterBank bank = new RegisterBank();
        bank.updateFromA3(TestFrames.sampleA3());
        ModbusRtuServer server = new ModbusRtuServer(null, Map.of(1, bank));
        byte[] write = Crc16.appendModbusCrc(new byte[]{1, 6, 0, 1, 0, 40});
        byte[] response = server.handle(write);
        assertArrayEquals(Crc16.appendModbusCrc(new byte[]{1, (byte) 0x86, 3}), response);
    }

    @Test
    void routesUnitIdsToSeparateAirConditioners() {
        RegisterBank first = new RegisterBank();
        RegisterBank second = new RegisterBank();
        first.updateFromA3(TestFrames.sampleA3());
        second.updateFromA3(TestFrames.sampleA3());
        ModbusRtuServer server = new ModbusRtuServer(null, Map.of(1, first, 2, second));

        byte[] writeSecond = Crc16.appendModbusCrc(new byte[]{2, 6, 0, 1, 0, 25});
        assertArrayEquals(writeSecond, server.handle(writeSecond));
        assertEquals(24, first.readHolding(1, 1)[0]);
        assertEquals(25, second.readHolding(1, 1)[0]);

        byte[] unknown = Crc16.appendModbusCrc(new byte[]{3, 3, 0, 1, 0, 1});
        assertNull(server.handle(unknown));
    }

    @Test
    void broadcastsWritesToEveryAirConditionerWithoutResponding() {
        RegisterBank first = new RegisterBank();
        RegisterBank second = new RegisterBank();
        first.updateFromA3(TestFrames.sampleA3());
        second.updateFromA3(TestFrames.sampleA3());
        ModbusRtuServer server = new ModbusRtuServer(null, Map.of(1, first, 2, second));

        byte[] broadcast = Crc16.appendModbusCrc(new byte[]{0, 6, 0, 0, 0, 1});
        assertNull(server.handle(broadcast));
        assertEquals(1, first.readHolding(0, 1)[0]);
        assertEquals(1, second.readHolding(0, 1)[0]);
    }
}

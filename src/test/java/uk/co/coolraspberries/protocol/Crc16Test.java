package uk.co.coolraspberries.protocol;

import org.junit.jupiter.api.Test;
import uk.co.coolraspberries.ac.AcProtocol;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Crc16Test {
    @Test
    void standardCheckValue() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x4B37, Crc16.modbus(input, 0, input.length));
    }

    @Test
    void acHeartbeatUsesHighByteFirst() {
        byte[] heartbeat = AcProtocol.heartbeat();
        assertArrayEquals(java.util.HexFormat.of().parseHex("7A7A21D50C0000AB0A0AFCF9"), heartbeat);
        assertTrue(Crc16.validAcFrame(heartbeat));
        assertFalse(Crc16.validModbusFrame(heartbeat));
    }

    @Test
    void modbusUsesLowByteFirst() {
        byte[] frame = Crc16.appendModbusCrc(new byte[]{1, 3, 0, 0, 0, 1});
        assertArrayEquals(java.util.HexFormat.of().parseHex("010300000001840A"), frame);
        assertTrue(Crc16.validModbusFrame(frame));
    }
}

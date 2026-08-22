package cr.modbus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModbusTrafficTest {
    @Test
    void countsMessagesByUnitAndSortsSnapshot() {
        ModbusTraffic traffic = new ModbusTraffic();
        traffic.recordRequest(11);
        traffic.recordRequest(3);
        traffic.recordResponse(11);
        traffic.recordRequest(11);

        assertEquals(
                java.util.List.of(
                        new ModbusTraffic.DeviceCount(3, 1, 0),
                        new ModbusTraffic.DeviceCount(11, 2, 1)),
                traffic.snapshot());
    }
}

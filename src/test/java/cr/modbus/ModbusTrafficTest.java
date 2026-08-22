package cr.modbus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModbusTrafficTest {
    @Test
    void countsMessagesByUnitAndSortsSnapshot() {
        ModbusTraffic traffic = new ModbusTraffic();
        traffic.record(11);
        traffic.record(3);
        traffic.record(11);

        assertEquals(
                java.util.List.of(
                        new ModbusTraffic.DeviceCount(3, 1),
                        new ModbusTraffic.DeviceCount(11, 2)),
                traffic.snapshot());
    }
}

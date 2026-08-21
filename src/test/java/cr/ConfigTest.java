package cr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsMultipleAirConditionersTlsAndUsers() throws Exception {
        Config config = Config.load(writeConfig(1, 2));

        assertEquals(2, config.airConditioners().size());
        assertEquals("Living room", config.airConditioners().get(0).name());
        assertEquals(2, config.airConditioners().get(1).modbusUnitId());
        assertEquals(8443, config.web().port());
        assertEquals("secret-one", config.web().users().get("admin"));
        assertEquals("secret-two", config.web().users().get("operator"));
    }

    @Test
    void rejectsDuplicateModbusUnitIds() throws Exception {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Config.load(writeConfig(1, 1)));
        assertEquals("duplicate Modbus unit ID: 1", error.getMessage());
    }

    @Test
    void allowsNoAirConditioners() throws Exception {
        Path path = temporaryDirectory.resolve("empty-gateway.properties");
        Files.writeString(path, """
                ac.instances=
                modbus.device=/dev/modbus
                web.tls.certificate=/etc/certificate.pem
                web.tls.privateKey=/etc/private-key.pem
                web.users=admin
                web.user.admin.password=secret
                """);

        Config config = Config.load(path);

        assertEquals(0, config.airConditioners().size());
    }

    private Path writeConfig(int firstUnit, int secondUnit) throws Exception {
        Path path = temporaryDirectory.resolve("gateway.properties");
        Files.writeString(path, """
                ac.instances=living,bedroom
                ac.living.name=Living room
                ac.living.device=/dev/living
                ac.living.modbusUnitId=%d
                ac.living.controllerMac=00:01:02:03:04:05
                ac.bedroom.name=Bedroom
                ac.bedroom.device=/dev/bedroom
                ac.bedroom.modbusUnitId=%d
                ac.bedroom.controllerMac=06:07:08:09:0a:0b
                modbus.device=/dev/modbus
                web.port=8443
                web.tls.certificate=/etc/certificate.pem
                web.tls.privateKey=/etc/private-key.pem
                web.users=admin,operator
                web.user.admin.password=secret-one
                web.user.operator.password=secret-two
                """.formatted(firstUnit, secondUnit));
        return path;
    }
}

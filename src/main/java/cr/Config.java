package cr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;

public record Config(
        Serial acSerial,
        Serial modbusSerial,
        int modbusUnitId,
        String webBind,
        int webPort,
        Path logPath,
        int logLimitBytes,
        int logFiles,
        byte[] controllerMac,
        Duration staleAfter) {

    public record Serial(String device, int baud, int dataBits, int stopBits, int parity) {
        public Serial {
            if (device == null || device.isBlank()) throw new IllegalArgumentException("serial device is required");
            if (baud <= 0) throw new IllegalArgumentException("serial baud must be positive");
            if (dataBits < 5 || dataBits > 8) throw new IllegalArgumentException("serial data bits must be 5..8");
            if (stopBits != 1 && stopBits != 2) throw new IllegalArgumentException("serial stop bits must be 1 or 2");
            if (parity < 0 || parity > 2) throw new IllegalArgumentException("serial parity must be 0, 1, or 2");
        }
    }

    public Config {
        if (acSerial.device().equals(modbusSerial.device())) {
            throw new IllegalArgumentException("AC and Modbus must use different serial devices");
        }
        if (modbusUnitId < 1 || modbusUnitId > 247) throw new IllegalArgumentException("modbus.unitId must be 1..247");
        if (webPort < 1 || webPort > 65535) throw new IllegalArgumentException("web.port must be 1..65535");
        if (logLimitBytes < 65_536) throw new IllegalArgumentException("log.limitBytes must be at least 65536");
        if (logFiles < 1) throw new IllegalArgumentException("log.files must be positive");
        if (controllerMac.length != 6) throw new IllegalArgumentException("controller.mac must contain six octets");
    }

    public static Config load(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            p.load(input);
        }
        return new Config(
                serial(p, "ac", 9600),
                serial(p, "modbus", 9600),
                integer(p, "modbus.unitId", 1),
                p.getProperty("web.bind", "127.0.0.1").trim(),
                integer(p, "web.port", 8080),
                Path.of(p.getProperty("log.path", "/var/log/cool-raspberries/gateway.log")),
                integer(p, "log.limitBytes", 5_000_000),
                integer(p, "log.files", 5),
                parseMac(p.getProperty("controller.mac", "00:00:00:00:00:00")),
                Duration.ofSeconds(integer(p, "ac.staleAfterSeconds", 30)));
    }

    private static Serial serial(Properties p, String prefix, int defaultBaud) {
        return new Serial(
                required(p, prefix + ".device"),
                integer(p, prefix + ".baud", defaultBaud),
                integer(p, prefix + ".dataBits", 8),
                integer(p, prefix + ".stopBits", 1),
                parseParity(p.getProperty(prefix + ".parity", "none")));
    }

    private static int parseParity(String value) {
        return switch (value.trim().toLowerCase()) {
            case "none", "n" -> 0;
            case "odd", "o" -> 1;
            case "even", "e" -> 2;
            default -> throw new IllegalArgumentException("parity must be none, odd, or even");
        };
    }

    private static byte[] parseMac(String value) {
        String compact = value.replace(":", "").replace("-", "").trim();
        if (compact.length() != 12) throw new IllegalArgumentException("controller.mac must contain 12 hex digits");
        return HexFormat.of().parseHex(compact);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }

    private static int integer(Properties p, String key, int defaultValue) {
        return Integer.parseInt(p.getProperty(key, Integer.toString(defaultValue)).trim());
    }
}

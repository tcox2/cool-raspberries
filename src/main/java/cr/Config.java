package cr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public record Config(
        List<AirConditioner> airConditioners,
        Serial modbusSerial,
        Web web,
        Path logPath,
        int logLimitBytes,
        int logFiles) {

    public record Serial(String device, int baud, int dataBits, int stopBits, int parity) {
        public Serial {
            if (device == null || device.isBlank()) throw new IllegalArgumentException("serial device is required");
            if (baud <= 0) throw new IllegalArgumentException("serial baud must be positive");
            if (dataBits < 5 || dataBits > 8) throw new IllegalArgumentException("serial data bits must be 5..8");
            if (stopBits != 1 && stopBits != 2) throw new IllegalArgumentException("serial stop bits must be 1 or 2");
            if (parity < 0 || parity > 2) throw new IllegalArgumentException("serial parity must be 0, 1, or 2");
        }
    }

    public record AirConditioner(
            String id,
            String name,
            Serial serial,
            int modbusUnitId,
            byte[] controllerMac,
            Duration staleAfter) {
        public AirConditioner {
            if (id == null || !id.matches("[a-zA-Z0-9_-]+")) {
                throw new IllegalArgumentException("AC id must contain only letters, digits, '_' or '-'");
            }
            if (name == null || name.isBlank()) throw new IllegalArgumentException("AC name is required");
            if (modbusUnitId < 1 || modbusUnitId > 247) {
                throw new IllegalArgumentException("AC Modbus unit ID must be 1..247");
            }
            if (controllerMac == null || controllerMac.length != 6) {
                throw new IllegalArgumentException("controller MAC must contain six octets");
            }
            if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
                throw new IllegalArgumentException("AC stale interval must be positive");
            }
            controllerMac = controllerMac.clone();
        }

        @Override
        public byte[] controllerMac() {
            return controllerMac.clone();
        }
    }

    public record Web(
            int port,
            Path certificatePath,
            Path privateKeyPath,
            String privateKeyPassword,
            Map<String, String> users) {
        public Web {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("web.port must be 1..65535");
            if (certificatePath == null) throw new IllegalArgumentException("web.tls.certificate is required");
            if (privateKeyPath == null) throw new IllegalArgumentException("web.tls.privateKey is required");
            privateKeyPassword = privateKeyPassword == null ? "" : privateKeyPassword;
            if (users == null || users.isEmpty()) throw new IllegalArgumentException("at least one web user is required");
            users.forEach((username, password) -> {
                if (username == null || username.isBlank() || username.contains(":")) {
                    throw new IllegalArgumentException("web usernames must be non-empty and cannot contain ':'");
                }
                if (password == null || password.isEmpty()) {
                    throw new IllegalArgumentException("web passwords cannot be empty");
                }
            });
            users = Map.copyOf(users);
        }
    }

    public Config {
        if (airConditioners == null) throw new IllegalArgumentException("air conditioners are required");
        airConditioners = List.copyOf(airConditioners);
        Set<String> ids = new HashSet<>();
        Set<Integer> unitIds = new HashSet<>();
        Set<String> devices = new HashSet<>();
        devices.add(modbusSerial.device());
        for (AirConditioner ac : airConditioners) {
            if (!ids.add(ac.id())) throw new IllegalArgumentException("duplicate AC id: " + ac.id());
            if (!unitIds.add(ac.modbusUnitId())) {
                throw new IllegalArgumentException("duplicate Modbus unit ID: " + ac.modbusUnitId());
            }
            if (!devices.add(ac.serial().device())) {
                throw new IllegalArgumentException("serial devices must be unique: " + ac.serial().device());
            }
        }
        if (logLimitBytes < 65_536) throw new IllegalArgumentException("log.limitBytes must be at least 65536");
        if (logFiles < 1) throw new IllegalArgumentException("log.files must be positive");
    }

    public static Config load(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            p.load(input);
        }
        List<AirConditioner> airConditioners = new ArrayList<>();
        for (String id : optionalList(p.getProperty("ac.instances", ""))) {
            String prefix = "ac." + id;
            airConditioners.add(new AirConditioner(
                    id,
                    p.getProperty(prefix + ".name", id).trim(),
                    serial(p, prefix, 9600),
                    integer(p, prefix + ".modbusUnitId", -1),
                    parseMac(required(p, prefix + ".controllerMac")),
                    Duration.ofSeconds(integer(p, prefix + ".staleAfterSeconds", 30))));
        }
        Map<String, String> users = new LinkedHashMap<>();
        for (String username : list(required(p, "web.users"))) {
            users.put(username, required(p, "web.user." + username + ".password"));
        }
        return new Config(
                airConditioners,
                serial(p, "modbus", 9600),
                new Web(
                        integer(p, "web.port", 8443),
                        Path.of(required(p, "web.tls.certificate")),
                        Path.of(required(p, "web.tls.privateKey")),
                        p.getProperty("web.tls.privateKeyPassword", ""),
                        users),
                Path.of(p.getProperty("log.path", "/tmp/cool-raspberries/gateway.log")),
                integer(p, "log.limitBytes", 5_000_000),
                integer(p, "log.files", 5));
    }

    private static Serial serial(Properties p, String prefix, int defaultBaud) {
        return new Serial(
                required(p, prefix + ".device"),
                integer(p, prefix + ".baud", defaultBaud),
                integer(p, prefix + ".dataBits", 8),
                integer(p, prefix + ".stopBits", 1),
                parseParity(p.getProperty(prefix + ".parity", "none")));
    }

    private static List<String> list(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) throw new IllegalArgumentException("configuration list contains an empty item");
            result.add(trimmed);
        }
        return result;
    }

    private static List<String> optionalList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return list(value);
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
        if (compact.length() != 12) throw new IllegalArgumentException("controller MAC must contain 12 hex digits");
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

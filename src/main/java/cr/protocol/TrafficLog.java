package cr.protocol;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class TrafficLog {
    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    private TrafficLog() {}

    public static String entry(String serial, String direction, byte[] bytes, String meaning) {
        return "serial=" + serial + " direction=" + direction + " bytes=\"" + HEX.formatHex(bytes)
                + "\" meaning=\"" + meaning + "\"";
    }

    public static String rawMeaning(String protocol) {
        return "raw " + protocol + " receive chunk; complete-message decoding follows when enough bytes arrive";
    }

    public static String acFrame(byte[] frame) {
        if (frame.length < 8) return "proprietary AC fragment too short to decode";
        int type = u(frame[7]);
        String envelope = "AC " + typeName(type) + " " + hexByte(frame[2]) + "→" + hexByte(frame[3])
                + ", length=" + frame.length + ", CRC=" + crcState(frame);
        return switch (type) {
            case 0xA1 -> frame.length >= 24 ? envelope + ", write configuration: "
                    + "power=" + bit(frame[12], 3)
                    + ", mode=" + mode(u(frame[12]) & 7)
                    + ", fan=" + ((u(frame[12]) >>> 4) & 7)
                    + ", setpoint=" + ((u(frame[13]) & 15) + 16) + "°C"
                    + ", turbo=" + bit(frame[12], 7)
                    + ", quiet=" + bit(frame[13], 6)
                    + ", sweepLR=" + ((u(frame[14]) >>> 4) & 15)
                    + ", sweepUD=" + (u(frame[14]) & 15)
                    + ", timerRaw=" + word(frame, 10)
                    : envelope + ", malformed A1 configuration frame";
            case 0xA2 -> envelope + ", provisional configuration/enrolment completion";
            case 0xA3 -> frame.length >= 34 ? envelope + ", operating state: "
                    + "returnAirRaw=" + u(frame[10]) + ":" + u(frame[11])
                    + ", power=" + bit(frame[13], 3)
                    + ", mode=" + mode(u(frame[13]) & 7)
                    + ", fan=" + ((u(frame[13]) >>> 4) & 7)
                    + ", setpoint=" + ((u(frame[14]) & 15) + 16) + "°C"
                    + ", timer=" + word(frame, 19) + "min"
                    + ", operatingHours=" + word(frame, 23)
                    : envelope + ", malformed A3 operating-state frame";
            case 0xA4 -> frame.length >= 13 ? envelope + ", remote-control event: " + remoteState(u(frame[10]))
                    : envelope + ", malformed A4 remote-control frame";
            case 0xA5 -> envelope + ", provisional enrolment/display command";
            case 0xA6 -> envelope + ", unknown A6 message";
            case 0xAB -> envelope + ", periodic keepalive";
            case 0xAC -> envelope + ", likely keepalive response";
            default -> envelope + ", unknown message type";
        };
    }

    public static String modbusRequest(byte[] frame) {
        if (frame.length < 2) return "Modbus RTU request fragment too short to decode";
        int unit = u(frame[0]);
        int function = u(frame[1]);
        String prefix = "Modbus request unit=" + unit + (unit == 0 ? " (broadcast)" : "")
                + ", function=" + functionName(function) + ", CRC=" + modbusCrcState(frame);
        if (frame.length < 6) return prefix + ", truncated request";
        return switch (function) {
            case 3, 4 -> prefix + ", address=" + word(frame, 2) + ", count=" + word(frame, 4);
            case 6 -> prefix + ", address=" + word(frame, 2) + ", value=" + word(frame, 4);
            case 16 -> prefix + ", address=" + word(frame, 2) + ", count=" + word(frame, 4)
                    + ", values=" + registerValues(frame, 7, frame.length - 2);
            default -> prefix + ", unsupported function";
        };
    }

    public static String modbusResponse(byte[] frame) {
        if (frame.length < 2) return "Modbus RTU response fragment too short to decode";
        int unit = u(frame[0]);
        int function = u(frame[1]);
        String prefix = "Modbus response unit=" + unit + ", CRC=" + modbusCrcState(frame);
        if ((function & 0x80) != 0) {
            return prefix + ", exception for function=" + functionName(function & 0x7F)
                    + ", code=" + (frame.length > 2 ? exceptionName(u(frame[2])) : "missing");
        }
        if (function == 3 || function == 4) {
            return prefix + ", function=" + functionName(function) + ", values="
                    + registerValues(frame, 3, frame.length - 2);
        }
        if (function == 6 && frame.length >= 6) {
            return prefix + ", write acknowledged: address=" + word(frame, 2) + ", value=" + word(frame, 4);
        }
        if (function == 16 && frame.length >= 6) {
            return prefix + ", multiple write acknowledged: address=" + word(frame, 2)
                    + ", count=" + word(frame, 4);
        }
        return prefix + ", function=" + functionName(function);
    }

    private static String crcState(byte[] frame) {
        return Crc16.validAcFrame(frame) ? "valid" : "INVALID";
    }

    private static String modbusCrcState(byte[] frame) {
        return Crc16.validModbusFrame(frame) ? "valid" : "INVALID";
    }

    private static String typeName(int type) {
        return switch (type) {
            case 0xA1 -> "A1";
            case 0xA2 -> "A2";
            case 0xA3 -> "A3";
            case 0xA4 -> "A4";
            case 0xA5 -> "A5";
            case 0xA6 -> "A6";
            case 0xAB -> "AB";
            case 0xAC -> "AC";
            default -> "type-" + hex(type);
        };
    }

    private static String functionName(int function) {
        return switch (function) {
            case 3 -> "03/read-holding";
            case 4 -> "04/read-input";
            case 6 -> "06/write-single";
            case 16 -> "16/write-multiple";
            default -> hex(function) + "/unknown";
        };
    }

    private static String exceptionName(int code) {
        return switch (code) {
            case 1 -> "1/illegal-function";
            case 2 -> "2/illegal-address";
            case 3 -> "3/illegal-value";
            case 4 -> "4/server-failure";
            case 6 -> "6/server-busy";
            default -> Integer.toString(code);
        };
    }

    private static String remoteState(int value) {
        return switch (value) {
            case 0 -> "enabled (raw=00)";
            case 1 -> "disabled (raw=01)";
            case 0xA5 -> "third/unknown state (raw=A5)";
            default -> "unknown raw=" + hex(value);
        };
    }

    private static String registerValues(byte[] frame, int start, int endExclusive) {
        List<Integer> values = new ArrayList<>();
        for (int offset = start; offset + 1 < endExclusive; offset += 2) values.add(word(frame, offset));
        return values.toString();
    }

    private static String mode(int value) {
        return switch (value) {
            case 0 -> "auto(0)";
            case 1 -> "cool(1)";
            case 2 -> "dry(2)";
            case 3 -> "fan(3)";
            case 4 -> "heat(4)";
            default -> "unknown(" + value + ")";
        };
    }

    private static int word(byte[] data, int offset) {
        return (u(data[offset]) << 8) | u(data[offset + 1]);
    }

    private static int bit(byte value, int bit) {
        return (u(value) >>> bit) & 1;
    }

    private static int u(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static String hexByte(byte value) {
        return hex(u(value));
    }

    private static String hex(int value) {
        return "%02X".formatted(value & 0xFF);
    }
}

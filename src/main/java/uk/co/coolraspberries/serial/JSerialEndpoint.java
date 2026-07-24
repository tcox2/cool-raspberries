package uk.co.coolraspberries.serial;

import com.fazecast.jSerialComm.SerialPort;
import uk.co.coolraspberries.Config;

import java.io.IOException;
public final class JSerialEndpoint implements SerialEndpoint {
    private final SerialPort port;

    private JSerialEndpoint(SerialPort port) {
        this.port = port;
    }

    public static JSerialEndpoint open(Config.Serial config) throws IOException {
        SerialPort port = SerialPort.getCommPort(config.device());
        int stopBits = config.stopBits() == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
        int parity = switch (config.parity()) {
            case 1 -> SerialPort.ODD_PARITY;
            case 2 -> SerialPort.EVEN_PARITY;
            default -> SerialPort.NO_PARITY;
        };
        port.setComPortParameters(config.baud(), config.dataBits(), stopBits, parity);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 250, 1_000);
        if (!port.openPort()) throw new IOException("cannot open serial port " + config.device());
        return new JSerialEndpoint(port);
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        int count = port.readBytes(destination, length, offset);
        if (count < 0) throw new IOException("serial read failed on " + description());
        return count;
    }

    @Override
    public void write(byte[] data) throws IOException {
        int count = port.writeBytes(data, data.length);
        if (count != data.length) throw new IOException("short serial write on " + description());
    }

    @Override
    public String description() {
        return port.getSystemPortPath();
    }

    @Override
    public void close() {
        port.closePort();
    }
}

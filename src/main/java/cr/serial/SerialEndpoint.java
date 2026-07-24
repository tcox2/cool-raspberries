package cr.serial;

import java.io.IOException;

public interface SerialEndpoint extends AutoCloseable {
    int read(byte[] destination, int offset, int length) throws IOException;
    void write(byte[] data) throws IOException;
    String description();
    @Override void close();
}

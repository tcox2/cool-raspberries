package cr.protocol;

import java.util.Arrays;
import java.util.Optional;

public final class AcFrameDecoder {
    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 128;
    private byte[] buffer = new byte[MAX_LENGTH];
    private int position;
    private int expectedLength;

    public Optional<byte[]> accept(int unsignedByte) {
        int value = unsignedByte & 0xFF;
        if (position == 0) {
            if (value == 0x7A) buffer[position++] = (byte) value;
            return Optional.empty();
        }
        if (position == 1) {
            if (value == 0x7A) {
                buffer[position++] = (byte) value;
            } else {
                position = 0;
            }
            return Optional.empty();
        }

        buffer[position++] = (byte) value;
        if (position == 5) {
            expectedLength = value;
            if (expectedLength < MIN_LENGTH || expectedLength > MAX_LENGTH) resetKeepingSyncCandidate(value);
            return Optional.empty();
        }
        if (expectedLength > 0 && position == expectedLength) {
            byte[] frame = Arrays.copyOf(buffer, expectedLength);
            resetKeepingSyncCandidate(-1);
            return Optional.of(frame);
        }
        return Optional.empty();
    }

    private void resetKeepingSyncCandidate(int lastByte) {
        position = 0;
        expectedLength = 0;
        if (lastByte == 0x7A) buffer[position++] = 0x7A;
    }
}

package uk.co.coolraspberries.protocol;

import org.junit.jupiter.api.Test;
import uk.co.coolraspberries.ac.AcProtocol;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AcFrameDecoderTest {
    @Test
    void decodesAcrossNoiseAndIndividualBytes() {
        AcFrameDecoder decoder = new AcFrameDecoder();
        assertTrue(decoder.accept(0x12).isEmpty());
        assertTrue(decoder.accept(0x7A).isEmpty());
        assertTrue(decoder.accept(0x00).isEmpty());

        Optional<byte[]> result = Optional.empty();
        byte[] expected = AcProtocol.heartbeat();
        for (byte value : expected) result = decoder.accept(Byte.toUnsignedInt(value));
        assertTrue(result.isPresent());
        assertArrayEquals(expected, result.orElseThrow());
    }

    @Test
    void rejectsInvalidLengthAndResynchronizes() {
        AcFrameDecoder decoder = new AcFrameDecoder();
        for (byte value : new byte[]{0x7A, 0x7A, 0x21, (byte) 0xD5, 0x03}) {
            assertTrue(decoder.accept(Byte.toUnsignedInt(value)).isEmpty());
        }
        Optional<byte[]> result = Optional.empty();
        for (byte value : AcProtocol.heartbeat()) result = decoder.accept(Byte.toUnsignedInt(value));
        assertArrayEquals(AcProtocol.heartbeat(), result.orElseThrow());
    }
}

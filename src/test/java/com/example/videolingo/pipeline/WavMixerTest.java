package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WavMixerTest {

    private static byte[] wav(int rate, short... samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + samples.length * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(samples.length * 2);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    @Test
    void decodesMonoPcm() {
        WavMixer.Clip clip = WavMixer.decode(wav(1000, (short) 1, (short) -2, (short) 3));
        assertEquals(1000, clip.sampleRate());
        assertEquals(3, clip.samples().length);
        assertEquals(-2, clip.samples()[1]);
        assertEquals(3, clip.durationMs());
    }

    @Test
    void rejectsNonWav() {
        assertThrows(JobFailure.class, () -> WavMixer.decode("not audio at all".getBytes()));
    }

    @Test
    void placesClipsAtTheirTimeAndMixesOverlaps() throws Exception {
        WavMixer mixer = new WavMixer(1000, 5);
        mixer.place(WavMixer.decode(wav(1000, (short) 100, (short) 100)), 2);
        mixer.place(WavMixer.decode(wav(1000, (short) 32700, (short) 32700)), 3);
        assertEquals(5, mixer.durationMs());

        Path file = Files.createTempFile("mix", ".wav");
        mixer.writeWav(file);
        WavMixer.Clip out = WavMixer.decode(Files.readAllBytes(file));
        Files.delete(file);
        assertEquals(0, out.samples()[0]);
        assertEquals(100, out.samples()[2]);
        assertEquals(Short.MAX_VALUE, out.samples()[3]); // 100 + 32700 clipped
        assertEquals(32700, out.samples()[4]);
    }

    @Test
    void growsPastTheInitialLength() {
        WavMixer mixer = new WavMixer(1000, 1);
        mixer.place(WavMixer.decode(wav(1000, (short) 5, (short) 5, (short) 5)), 10);
        assertEquals(13, mixer.durationMs());
    }
}

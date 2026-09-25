package com.example.videolingo.pipeline;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

// Builds one mono 16-bit PCM track by placing clips at given times — the
// dub timeline. All clips must share the timeline's sample rate (the 24 kHz
// 16-bit mono PCM TextToSpeechClient produces). Overlaps are summed and clipped.
public final class WavMixer {

    /** Decoded PCM from a WAV file. */
    public record Clip(short[] samples, int sampleRate) {
        public long durationMs() {
            return samples.length * 1000L / sampleRate;
        }
    }

    private final int sampleRate;
    private short[] track;
    private int length;

    public WavMixer(int sampleRate, long initialMs) {
        this.sampleRate = sampleRate;
        this.track = new short[(int) Math.max(1, initialMs * sampleRate / 1000)];
    }

    public void place(Clip clip, long atMs) {
        if (clip.sampleRate() != sampleRate) {
            throw new IllegalArgumentException("Clip is " + clip.sampleRate() + " Hz, timeline is " + sampleRate + " Hz");
        }
        int offset = (int) (atMs * sampleRate / 1000);
        int end = offset + clip.samples().length;
        if (end > track.length) {
            short[] bigger = new short[Math.max(end, track.length + track.length / 2)];
            System.arraycopy(track, 0, bigger, 0, track.length);
            track = bigger;
        }
        for (int i = 0; i < clip.samples().length; i++) {
            int mixed = track[offset + i] + clip.samples()[i];
            track[offset + i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
        }
        length = Math.max(length, end);
    }

    public long durationMs() {
        return length * 1000L / sampleRate;
    }

    public void writeWav(Path file) throws IOException {
        int dataBytes = length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(dataBytes);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header.array());
            ByteBuffer data = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < length; i++) {
                if (data.remaining() < 2) {
                    out.write(data.array(), 0, data.position());
                    data.clear();
                }
                data.putShort(track[i]);
            }
            out.write(data.array(), 0, data.position());
        }
    }

    /** Reads a 16-bit mono PCM WAV (walks the chunks, so extra LIST/fact chunks are fine). */
    public static Clip decode(byte[] wav) {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 12 || !"RIFF".equals(new String(wav, 0, 4)) || !"WAVE".equals(new String(wav, 8, 4))) {
            throw new JobFailure("Text-to-speech returned audio that isn't a WAV file");
        }
        int pos = 12;
        int rate = 0;
        int channels = 0;
        int bits = 0;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4);
            int size = b.getInt(pos + 4);
            int body = pos + 8;
            if ("fmt ".equals(id)) {
                channels = b.getShort(body + 2);
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
            } else if ("data".equals(id)) {
                if (channels != 1 || bits != 16) {
                    throw new JobFailure("Expected 16-bit mono speech audio, got " + bits + "-bit, " + channels + " channel(s)");
                }
                int n = Math.min(size, wav.length - body) / 2;
                short[] samples = new short[n];
                for (int i = 0; i < n; i++) {
                    samples[i] = b.getShort(body + i * 2);
                }
                return new Clip(samples, rate);
            }
            pos = body + size + (size & 1);
        }
        throw new JobFailure("Text-to-speech audio had no data");
    }
}

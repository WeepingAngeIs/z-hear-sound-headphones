package dev.mobsear.client;

import java.io.ByteArrayOutputStream;

/** The CI Java Sound device generates a voiced harmonic tone and samples the real playback sink. */
public final class PitchFixtureAudio {
    public static volatile boolean enabled;
    private static final byte[] PERIOD = new byte[160];
    private static ByteArrayOutputStream captured;
    static {
        for (int i = 0; i < 80; i++) {
            double a = 2 * Math.PI * i / 80;
            int sample = (int)(7500 * (Math.sin(a) + .26*Math.sin(2*a) + .16*Math.sin(3*a) + .07*Math.sin(5*a)));
            PERIOD[2*i] = (byte)sample;
            PERIOD[2*i+1] = (byte)(sample >> 8);
        }
    }
    private PitchFixtureAudio() {}
    static void fill(byte[] input, int offset, int count) {
        for (int i = 0; i < count; i++) input[offset+i] = PERIOD[i % PERIOD.length];
    }
    static synchronized void write(byte[] data, int offset, int count) {
        if (captured != null && captured.size() < 2 * 32000) captured.write(data,offset,count);
    }
    public static synchronized void arm() { captured = new ByteArrayOutputStream(32000); }
    public static synchronized int bytes() { return captured == null ? 0 : captured.size(); }
    public static synchronized byte[] finish() {
        var samples = captured.toByteArray();
        captured = null;
        return samples;
    }
}

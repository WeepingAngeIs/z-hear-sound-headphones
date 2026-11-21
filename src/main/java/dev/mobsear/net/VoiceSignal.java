package dev.mobsear.net;

/** Stateless mono signed little-endian PCM16 measurement used by both capture and relay. */
public final class VoiceSignal {
    private VoiceSignal() {}

    public static double rms(byte[] pcm) {
        if (pcm.length < 2 || (pcm.length & 1) != 0) return 0;
        long squares = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            int sample = (pcm[i] & 255) | pcm[i + 1] << 8;
            squares += (long) sample * sample;
        }
        return Math.sqrt(squares / (pcm.length / 2.0)) / 32768.0;
    }

    /** Volume meter spans about -54 to -6 dBFS, normalized for drawing. */
    public static double meter(double rms) {
        if (!(rms > 0.002)) return 0;
        return Math.clamp((20 * Math.log10(rms) + 54) / 48, 0, 1);
    }
}

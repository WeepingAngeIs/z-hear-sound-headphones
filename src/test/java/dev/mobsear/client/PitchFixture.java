package dev.mobsear.client;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory fixed-rate synthetic audio. No mixer, filesystem or Minecraft runtime. */
final class PitchFixture {
    private static final int RATE = VoiceAudio.SAMPLE_RATE;
    private static final int SAMPLES = VoiceAudio.FRAME_BYTES / 2;

    static void check() {
        var pitch = new OutgoingPitch();
        for (int cents : new int[]{0, VoicePreferences.DEEP_CENTS, VoicePreferences.SQUEAKY_CENTS,
                -1200, 1200, 317, -239, 0}) {
            checkTone(pitch, cents, 400);
            checkTone(pitch, cents, 175);
        }
        var calls = new AtomicInteger();
        byte[] dry = tone(0, 400);
        require(!pitch.route(dry, 700, pcm -> false, pcm -> calls.incrementAndGet()) && calls.get() == 0,
                "A dropped outbound frame reached recognition");
        var wire = new AtomicReference<byte[]>();
        byte[] silence = new byte[VoiceAudio.FRAME_BYTES];
        pitch.route(silence, 700, pcm -> { wire.set(pcm); return true; }, pcm -> {});
        require(Arrays.equals(wire.get(), silence), "Previous press leaked through the delay after a dropped frame");
        pitch.reset();
        pitch.route(dry, 0, pcm -> { wire.set(pcm); return true; }, pcm -> {});
        require(wire.get() == dry, "Returning to natural did not bypass the previous effect");
    }

    private static void checkTone(OutgoingPitch pitch, int cents, int frequency) {
        pitch.reset();
        int firstSignal = -1, crossings = 0, previous = 0, peak = 0;
        long squares = 0;
        var wire = new AtomicReference<byte[]>();
        var dryFrames = new AtomicInteger();
        for (int i = 0; i < 55; i++) {
            byte[] dry = tone(i, frequency);
            byte[] copy = dry.clone();
            require(pitch.route(dry, cents, pcm -> { wire.set(pcm); return true; }, raw -> {
                require(raw == dry, "Local recognizer did not receive the original PCM");
                dryFrames.incrementAndGet();
            }), "Valid frame dropped");
            byte[] out = wire.get();
            require(out.length == VoiceAudio.FRAME_BYTES && Arrays.equals(dry, copy), "Frame duration or dry audio changed");
            if (cents == 0) require(out == dry, "Natural must be byte exact");
            for (int j = 0; j < SAMPLES; j++) {
                int sample = (out[2 * j] & 255) | out[2 * j + 1] << 8;
                if (firstSignal < 0 && Math.abs(sample) > 400) firstSignal = i * SAMPLES + j;
                if (i >= 5) {
                    if (previous <= 0 && sample > 0) crossings++;
                    peak = Math.max(peak, Math.abs(sample));
                    squares += (long)sample * sample;
                }
                previous = sample;
            }
        }
        double target = frequency * Math.pow(2, cents / 1200.0);
        double rms = Math.sqrt((double)squares / RATE);
        require(dryFrames.get() == 55 && firstSignal >= 0 && firstSignal < 640, "Pitch startup exceeded 40 ms");
        require(Math.abs(crossings - target) < Math.max(3, target * 0.04),
                "Pitch " + cents + " expected " + target + " Hz but received " + crossings + " Hz");
        require(peak < 14_000 && rms > 7_200 && rms < 9_500, "Unstable pitch gain or clipping");
        System.out.printf(java.util.Locale.ROOT,
                "PITCH_FIXTURE source=%dHz cents=%d expected=%.1fHz measured=%dHz onset=%.1fms peak=%d rms=%.0f%n",
                frequency, cents, target, crossings, firstSignal * 1000.0 / RATE, peak, rms);
    }

    private static byte[] tone(int frame, int frequency) {
        byte[] result = new byte[VoiceAudio.FRAME_BYTES];
        for (int i = 0; i < SAMPLES; i++) {
            int sample = (int)(12_000 * Math.sin(2 * Math.PI * frequency * (frame * SAMPLES + i) / RATE));
            result[2 * i] = (byte)sample;
            result[2 * i + 1] = (byte)(sample >> 8);
        }
        return result;
    }

    private static void require(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}

package dev.mobsear.client;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory fixed-rate synthetic audio. No mixer, filesystem or Minecraft runtime. */
final class PitchFixture {
    private static final int RATE = VoiceAudio.SAMPLE_RATE;
    private static final int SAMPLES = VoiceAudio.FRAME_BYTES / 2;

    static void check() {
        checkReverb();
        var pitch = new OutgoingVoice();
        for (int cents : new int[]{0, VoicePreferences.DEEP_CENTS, VoicePreferences.SQUEAKY_CENTS,
                -1200, 1200, 317, -239, 0}) {
            checkTone(pitch, cents, 400);
            checkTone(pitch, cents, 175);
        }
        byte[] dry = tone(0, 400);
        require(!pitch.route(dry, 700, 0, pcm -> false), "A rejected frame was reported as transmitted");
        var wire = new AtomicReference<byte[]>();
        byte[] silence = new byte[VoiceAudio.FRAME_BYTES];
        pitch.route(silence, 700, 0, pcm -> { wire.set(pcm); return true; });
        require(Arrays.equals(wire.get(), silence), "Previous press leaked through the delay after a dropped frame");
        pitch.reset();
        pitch.route(dry, 0, 0, pcm -> { wire.set(pcm); return true; });
        require(wire.get() == dry, "Returning to natural did not bypass the previous effect");
    }

    private static void checkReverb() {
        var effect = new OutgoingVoice();
        byte[] impulse = new byte[VoiceAudio.FRAME_BYTES];
        impulse[1] = 100;
        byte[] zero = new byte[impulse.length];
        byte[] original = impulse.clone();
        var wire = new AtomicReference<byte[]>();
        long[] energy = new long[40];
        for (int f=0; f<energy.length; f++) {
            byte[] input = f == 0 ? impulse : zero;
            require(effect.route(input, 0, 100, data -> { wire.set(data); return true; }), "Room rejected frame");
            for (int n=0; n<wire.get().length; n+=2) {
                int sample = (wire.get()[n]&255) | wire.get()[n+1]<<8;
                if (f==0 && n==0) require(sample>12000, "Reverb delayed the direct voice");
                energy[f] += (long)sample*sample;
            }
        }
        require(Arrays.equals(impulse,original) && Arrays.equals(zero,new byte[zero.length]), "Reverb mutated source audio");
        require(energy[1]>0 && energy[3]>0 && energy[6]>0 && energy[6]<energy[1] && energy[39]==0,
                "Missing room reflections or a non-decaying tail");
        for (boolean drop : new boolean[]{false,true}) {
            effect.route(impulse, 0, 70, data -> true);
            if (drop) {
                require(!effect.route(zero, 0, 70, data -> false), "Dropped frame was reported as accepted");
            } else {
                effect.route(zero, 0, 0, data -> { require(data==zero,"Bypass must be bit-identical"); return true; });
            }
            for (int n=0; n<5; n++) effect.route(zero, 0, 70,
                    data -> { require(Arrays.equals(data,zero),"Old room tail leaked into next transmission"); return true; });
        }
        System.out.println("REVERB_FIXTURE direct onset, decaying reflections, dry recognition, bypass and dropped-frame reset");
    }

    private static void checkTone(OutgoingVoice pitch, int cents, int frequency) {
        pitch.reset();
        int firstSignal = -1, crossings = 0, previous = 0, peak = 0;
        long squares = 0;
        var wire = new AtomicReference<byte[]>();
        for (int i = 0; i < 55; i++) {
            byte[] dry = tone(i, frequency);
            byte[] copy = dry.clone();
            require(pitch.route(dry, cents, 0, pcm -> { wire.set(pcm); return true; }), "Valid frame dropped");
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
        require(firstSignal >= 0 && firstSignal < 640, "Pitch startup exceeded 40 ms");
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

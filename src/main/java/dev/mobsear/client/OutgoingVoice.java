package dev.mobsear.client;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Pitch and room reflections on the outbound branch of the 16 kHz mono stream. */
final class OutgoingVoice {
    private static final int HOP = 80;  // Four 5 ms synthesis hops per network frame.
    private static final int SEARCH = 104;  // ±6.5 ms, enough to find the nearest cycle in typical speech.
    private static final int RING = 1024;
    private static final int TAPS = 25;
    private final double[] samples = new double[RING], raw = new double[32];
    private final double[] lowpass = new double[TAPS], tail = new double[HOP], fade = new double[HOP];
    private int cents, delay;
    private long written;
    private double ratio;
    private final RoomReverb room = new RoomReverb();

    OutgoingVoice() {
        for (int i = 0; i < HOP; i++) fade[i] = 0.5 - 0.5 * Math.cos(Math.PI * (i + 0.5) / HOP);
    }

    boolean route(byte[] dry, int requestedCents, int reverbAmount, Predicate<byte[]> enqueue, Consumer<byte[]> recognize) {
        if (dry.length != VoiceAudio.FRAME_BYTES) return false;
        int next = Math.clamp(requestedCents, -1200, 1200);
        if (next != cents) configure(next);
        byte[] wire = cents == 0 ? dry : shift(dry);
        if (!enqueue.test(room.mix(wire, reverbAmount))) {
            reset();
            return false;
        }
        recognize.accept(dry);
        return true;
    }

    private void configure(int next) {
        resetPitch();
        cents = next;
        if (next == 0) return;
        ratio = Math.pow(2, next / 1200.0);
        // The current 20 ms frame is already present. Even the last hop can read its entire grain.
        delay = (int) Math.ceil((2 * HOP - 1) * ratio) + SEARCH + 2 - HOP;
        if (ratio > 1) {
            double cutoff = 0.46 / ratio;
            double sum = 0;
            for (int i = 0; i < TAPS; i++) {
                int x = i - TAPS / 2;
                double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
                lowpass[i] = sinc * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (TAPS - 1)));
                sum += lowpass[i];
            }
            for (int i = 0; i < TAPS; i++) lowpass[i] /= sum;
        }
    }

    private byte[] shift(byte[] dry) {
        long start = written;
        for (int i = 0; i < dry.length; i += 2) {
            double value = (dry[i] & 255) | (dry[i + 1] << 8);
            if (ratio > 1) {
                raw[(int)written & 31] = value;
                value = 0;
                for (int k = 0; k < TAPS; k++) value += raw[(int)(written-k) & 31] * lowpass[k];
            }
            samples[(int)written & (RING - 1)] = value;
            written++;
        }
        byte[] out = new byte[dry.length];
        for (int hop = 0; hop < dry.length / 2; hop += HOP) {
            long grain = align(start + hop - delay);
            for (int j = 0; j < HOP; j++) {
                double newest = sample(grain + j * ratio);
                double mixed = tail[j] * (1 - fade[j]) + newest * fade[j];
                int pcm = Math.clamp((int)Math.round(mixed), Short.MIN_VALUE, Short.MAX_VALUE);
                int offset = (hop + j) * 2;
                out[offset] = (byte)pcm;
                out[offset + 1] = (byte)(pcm >> 8);
                tail[j] = sample(grain + (j + HOP) * ratio);
            }
        }
        return out;
    }

    private long align(long nominal) {
        double oldPower = 0;
        for (int j = 0; j < HOP; j += 4) oldPower += tail[j] * tail[j];
        if (oldPower < 1) return nominal;
        long best = nominal;
        double bestScore = -Double.MAX_VALUE;
        for (int off = -SEARCH; off <= SEARCH; off++) {
            long pos = nominal + off;
            double dot = 0, power = 0;
            for (int j = 0; j < HOP; j += 4) {
                double value = sample(pos + j * ratio);
                dot += tail[j] * value;
                power += value * value;
            }
            // Prefer the closest equal-quality match. The nominal position always advances in real time.
            double score = dot / Math.sqrt(1 + oldPower * power) - 0.015 * Math.abs(off) / SEARCH;
            if (score > bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    private double sample(double position) {
        if (position < 0 || position < written - RING || position + 1 >= written) return 0;
        long index = (long)position;
        double left = samples[(int)index & (RING - 1)];
        double right = samples[(int)(index + 1) & (RING - 1)];
        return left + (position - index) * (right - left);
    }

    void reset() {
        resetPitch();
        room.reset();
    }

    private void resetPitch() {
        if (cents == 0 && written == 0) return;
        Arrays.fill(samples, 0);
        Arrays.fill(raw, 0);
        Arrays.fill(tail, 0);
        written = 0;
        cents = 0;
    }
}

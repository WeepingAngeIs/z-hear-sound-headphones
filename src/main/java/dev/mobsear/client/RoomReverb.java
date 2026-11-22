package dev.mobsear.client;

import java.util.Arrays;

/** Four damped, unequal delay lines model a small room without delaying the direct voice. */
final class RoomReverb {
    private static final class Reflection {
        final float[] line;
        final int earlyOffset;
        final float feedback;
        int cursor;
        float damped;

        Reflection(int length, int earlyOffset, float feedback) {
            this.line = new float[length];
            this.earlyOffset = earlyOffset;
            this.feedback = feedback;
        }

        float sample(int input) {
            int tap = cursor + earlyOffset;
            if (tap >= line.length) tap -= line.length;
            float early = line[tap];
            float late = line[cursor];
            damped += 0.63f * (late - damped);
            // Normalized feedback and convex mixing keep even a full-scale input bounded.
            line[cursor] = (1f - feedback) * input + feedback * damped;
            if (++cursor == line.length) cursor = 0;
            return 0.32f * early + 0.68f * late;
        }

        void clear() {
            Arrays.fill(line, 0);
            damped = 0;
            cursor = 0;
        }
    }

    // At 16 kHz the first reflection arrives at 11 ms. The direct path starts at sample zero.
    private final Reflection[] reflections = {
            new Reflection(379, 203, .51f), new Reflection(487, 251, .56f),
            new Reflection(593, 269, .59f), new Reflection(701, 317, .62f)
    };
    private float wet;
    private boolean active;

    byte[] mix(byte[] input, int amount) {
        if (amount <= 0) {
            reset();
            return input;
        }
        active = true;
        float target = Math.min(amount, 100) * .0055f;
        float step = (target - wet) / (input.length / 2);
        byte[] output = new byte[input.length];
        for (int i=0; i<input.length; i+=2) {
            int dry = (input[i] & 255) | input[i+1] << 8;
            float reflectionsSum = 0;
            for (Reflection reflection : reflections) reflectionsSum += reflection.sample(dry);
            wet += step;
            int sample = Math.clamp(Math.round(dry + wet * (reflectionsSum * .25f - dry)), Short.MIN_VALUE, Short.MAX_VALUE);
            output[i] = (byte) sample;
            output[i+1] = (byte) (sample >> 8);
        }
        wet = target;
        return output;
    }

    void reset() {
        if (!active) return;
        for (Reflection reflection : reflections) reflection.clear();
        wet = 0;
        active = false;
    }
}

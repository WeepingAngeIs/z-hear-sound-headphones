package dev.mobsear.client;

import dev.mobsear.net.VoiceSignal;
import java.util.ArrayDeque;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Processes the existing 20 ms input frames. All audio retained here is volatile pre-roll. */
final class CaptureGate {
    private record Frame(byte[] outbound, byte[] original) {}
    private final ArrayDeque<Frame> preRoll = new ArrayDeque<>(6);
    private int tail;
    private double previousSample;
    private double previousHighPass;
    private double envelope;
    private boolean filtering;
    private double level;

    double level() { return level; }

    void accept(byte[] pcm, boolean automatic, int sensitivity, boolean suppress, boolean recognize,
                BiConsumer<byte[], byte[]> transmit, Runnable endUtterance) {
        route(pcm, automatic, sensitivity, suppress, recognize, (wire, source) -> {
            transmit.accept(wire, source);
            return true;
        }, endUtterance);
    }

    /** A word may start or end below the voice activation threshold. Keep its original edges
     * for local analysis while requiring actually queued speech before any call can be emitted. */
    void capture(byte[] pcm, boolean automatic, int sensitivity, boolean suppress, Predicate<byte[]> transmit,
                 BiConsumer<byte[], Boolean> analyze, Runnable endTransmission) {
        if (pcm.length != VoiceAudio.FRAME_BYTES) return;
        byte[] source = analyze != null && suppress ? pcm.clone() : pcm;
        boolean queued = route(pcm, automatic, sensitivity, suppress, false, (wire, ignored) -> transmit.test(wire), endTransmission);
        if (analyze != null) analyze.accept(source, queued);
    }

    private boolean route(byte[] pcm, boolean automatic, int sensitivity, boolean suppress, boolean recognize,
                          BiPredicate<byte[],byte[]> transmit, Runnable endTransmission) {
        if (pcm.length != VoiceAudio.FRAME_BYTES) return false;
        byte[] original = recognize && suppress ? pcm.clone() : pcm;
        if (suppress) suppress(pcm);
        else if (filtering) resetFilter();
        filtering = suppress;
        double rms = VoiceSignal.rms(pcm);
        level = VoiceSignal.meter(rms);
        if (!automatic) return transmit.test(pcm, original);

        // Sensitivity spans -20 dBFS at 0 to -52 dBFS at 100. Hangover protects word endings.
        double threshold = Math.pow(10, (-20 - Math.clamp(sensitivity, 0, 100) * 0.32) / 20.0);
        if (rms >= threshold) {
            while (!preRoll.isEmpty()) {
                Frame prior = preRoll.removeFirst();
                transmit.test(prior.outbound, prior.original);
            }
            tail = 12;
            return transmit.test(pcm, original);
        } else if (tail > 0) {
            boolean queued = transmit.test(pcm, original);
            if (--tail == 0) endTransmission.run();
            return queued;
        } else {
            if (preRoll.size() == 6) preRoll.removeFirst();
            preRoll.addLast(new Frame(pcm, original));
            return false;
        }
    }

    private void suppress(byte[] pcm) {
        // One-pole ~80 Hz high-pass removes DC/rumble. A softly closing low-level gate
        // attenuates the steady noise floor between words while allowing speech transients.
        for (int i = 0; i < pcm.length; i += 2) {
            int input = (pcm[i] & 255) | pcm[i + 1] << 8;
            double high = 0.9691 * (previousHighPass + input - previousSample);
            previousHighPass = high;
            previousSample = input;
            envelope = Math.max(Math.abs(high), envelope * 0.998);
            double gain = Math.clamp((envelope - 55) / 125.0, 0, 1);
            int sample = Math.clamp((int) Math.round(high * gain), -32768, 32767);
            pcm[i] = (byte) sample;
            pcm[i + 1] = (byte) (sample >> 8);
        }
    }

    private void resetFilter() { previousSample = previousHighPass = envelope = 0; }
    void reset() {
        preRoll.clear();
        tail = 0;
        level = 0;
        filtering = false;
        resetFilter();
    }
}

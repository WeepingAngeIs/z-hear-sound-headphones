package dev.mobsear.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One bounded queue per speaker. The audio session serializes access to this mixer. */
final class FrameMixer {
    static final int MAX_SPEAKERS = 24;
    static final int MAX_FRAMES_PER_SPEAKER = 8;

    private record Frame(byte[] samples, double gain) {}
    private final Map<UUID, ArrayDeque<Frame>> queues = new HashMap<>();

    boolean offer(UUID id, byte[] samples, double gain) {
        if (id == null || samples == null || samples.length != VoiceAudio.FRAME_BYTES) {
            return false;
        }
        if (!Double.isFinite(gain) || gain <= 0) {
            queues.remove(id);
            return false;
        }
        var frames = queues.get(id);
        if (frames == null) {
            if (queues.size() >= MAX_SPEAKERS) {
                return false;
            }
            frames = new ArrayDeque<>();
            queues.put(id, frames);
        }
        if (frames.size() >= MAX_FRAMES_PER_SPEAKER) {
            frames.removeFirst();
        }
        // Distance attenuation is multiplied by the master and per-player sliders (up to 2x each).
        frames.addLast(new Frame(samples.clone(), Math.min(4.0, gain)));
        return true;
    }

    boolean isEmpty() {
        return queues.isEmpty();
    }

    void clear() {
        queues.clear();
    }

    byte[] mixNext() {
        double[] mixed = new double[VoiceAudio.FRAME_BYTES / 2];
        var speakers = queues.values().iterator();
        while (speakers.hasNext()) {
            var frames = speakers.next();
            var frame = frames.removeFirst();
            byte[] bytes = frame.samples();
            for (int i = 0; i < mixed.length; i++) {
                int low = bytes[2 * i] & 0xff;
                int high = bytes[2 * i + 1] << 8;
                mixed[i] += (low | high) * frame.gain();
            }
            if (frames.isEmpty()) {
                speakers.remove();
            }
        }
        byte[] output = new byte[VoiceAudio.FRAME_BYTES];
        for (int i = 0; i < mixed.length; i++) {
            int sample = (int) Math.clamp(Math.round(mixed[i]), Short.MIN_VALUE, Short.MAX_VALUE);
            output[2 * i] = (byte) sample;
            output[2 * i + 1] = (byte) (sample >> 8);
        }
        return output;
    }
}

package dev.mobsear.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Only the level and arrival time of audible audio influence the local speaking indicator. */
final class SpeakingActivity {
    private static final long HANGOVER = TimeUnit.MILLISECONDS.toNanos(360);
    private final Map<UUID, Long> lastAudible = new HashMap<>();
    private long lastSweep;

    void heard(UUID player, byte[] pcm, double gain, long now) {
        if (now - lastSweep > TimeUnit.SECONDS.toNanos(2) || lastSweep == 0) {
            lastAudible.values().removeIf(last -> now - last >= HANGOVER);
            lastSweep = now;
        }
        if (!(gain > 0) || pcm.length != VoiceAudio.FRAME_BYTES) return;
        long energy = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            int sample = (pcm[i] & 0xff) | (pcm[i + 1] << 8);
            energy += (long) sample * sample;
        }
        if (energy * gain * gain > 100.0 * 100.0 * (pcm.length / 2)) lastAudible.put(player, now);
    }

    boolean speaking(UUID player, long now) {
        Long last = lastAudible.get(player);
        return last != null && now - last < HANGOVER;
    }
}

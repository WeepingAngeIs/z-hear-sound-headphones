package dev.mobsear.server;

import dev.mobsear.net.CallFailure;
import java.util.concurrent.TimeUnit;

/** One authenticated player's short-lived server-side transmission budget. */
final class TalkWindow {
    private static final long FRAME_TIME = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long BURST_TIME = 12 * FRAME_TIME;
    // Recognition permits four seconds after the utterance endpoint. Allow one more for delivery.
    private static final long CUE_WINDOW = TimeUnit.SECONDS.toNanos(5);
    private static final long CUE_COOLDOWN = TimeUnit.MILLISECONDS.toNanos(1250);
    private long budget = BURST_TIME;
    private long updatedAt;
    private long lastFrame;
    private long lastCue;
    private boolean spoke;
    private boolean usedCue;
    private boolean freshAudio;

    TalkWindow(long now) { updatedAt = now; }

    boolean acceptFrame(long now) {
        budget = Math.min(BURST_TIME, budget + Math.min(BURST_TIME, Math.max(0, now - updatedAt)));
        updatedAt = now;
        if (budget < FRAME_TIME) return false;
        budget -= FRAME_TIME;
        lastFrame = now;
        spoke = true;
        freshAudio = true;
        return true;
    }

    boolean acceptCue(long now) {
        return tryCue(now) == null;
    }

    CallFailure tryCue(long now) {
        if (!spoke || !freshAudio || now - lastFrame > CUE_WINDOW) return CallFailure.NO_RECENT_VOICE;
        if (usedCue && now - lastCue < CUE_COOLDOWN) return CallFailure.COOLDOWN;
        lastCue = now;
        usedCue = true;
        freshAudio = false;
        return null;
    }
}

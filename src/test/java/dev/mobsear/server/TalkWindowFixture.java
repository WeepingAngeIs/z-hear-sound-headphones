package dev.mobsear.server;

import java.util.concurrent.TimeUnit;

public final class TalkWindowFixture {
    public static void main(String[] args) {
        long start = -10_000_000L; // nanoTime has an arbitrary origin.
        var window = new TalkWindow(start);
        require(!window.acceptCue(start), "cue before first voice frame");
        for (int i = 0; i < 12; i++) require(window.acceptFrame(start), "initial voice burst");
        require(!window.acceptFrame(start), "burst overflow");
        require(!window.acceptFrame(start + ms(19)), "early refill");
        require(window.acceptFrame(start + ms(20)), "one frame refill");
        require(window.acceptCue(start + ms(20)), "first cue");
        require(!window.acceptCue(start + ms(1300)), "repeat without new audio");
        require(window.acceptFrame(start + ms(1300)), "new frame");
        require(window.acceptCue(start + ms(1300)), "second utterance");
        require(window.acceptFrame(start + ms(1400)), "third frame");
        require(!window.acceptCue(start + ms(1400)), "cue rate limit");
        require(!window.acceptCue(start + ms(4401)), "stale voice");
        System.out.println("Voice burst, refill, freshness and cue cooldown passed.");
    }

    private static long ms(long millis) { return TimeUnit.MILLISECONDS.toNanos(millis); }
    private static void require(boolean result, String check) {
        if (!result) throw new AssertionError(check);
    }
}

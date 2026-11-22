package dev.mobsear.client;

/** The pinned reference analyzed frames as they left the original activation gate. */
final class FixtureCapture {
    static void accept(CaptureGate gate, SpeechCues.Utterances words, byte[] frame, boolean automatic) {
        gate.accept(frame, automatic, 50, true, true, (wire, raw) -> words.accept(raw), words::finish);
    }
}

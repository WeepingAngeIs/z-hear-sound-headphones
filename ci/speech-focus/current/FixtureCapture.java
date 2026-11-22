package dev.mobsear.client;

/** Feed the production capture path, with a synthetic proximity connection accepting frames. */
final class FixtureCapture {
    static void accept(CaptureGate gate, SpeechCues.Utterances words, byte[] frame, boolean automatic) {
        gate.capture(frame, automatic, 50, true, wire -> true, words::accept, () -> {});
    }
}

package dev.mobsear.client;

import dev.mobsear.net.Cue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Deadline and recovery tests use a worker double, independent of acoustic accuracy. */
public final class WorkerRecoveryFixture {
    private static SpeechCues speech(java.util.function.Consumer<Cue> listener) {
        return new SpeechCues(listener, id->{}, id->{}, null, java.util.Map.of(), java.util.Map.of(), proc->{},
                tracked -> new ProcessBuilder(Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),
                        "-NoProfile","-NonInteractive","-File",Path.of("ci/speech-lifecycle/resources/dev/mobsear/client/recognize.ps1").toAbsolutePath().toString()).start());
    }

    private static void await(java.util.function.BooleanSupplier check, int seconds) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while (!check.getAsBoolean() && System.nanoTime()<until) Thread.sleep(25);
        if (!check.getAsBoolean()) throw new AssertionError("Worker state deadline");
    }

    private static void submit(SpeechCues speech) {
        byte[] pcm=new byte[640];
        for (int i=1;i<pcm.length;i+=2) pcm[i]=8;
        for (int i=0;i<20;i++) speech.acceptFrame(pcm);
        speech.endUtterance();
    }

    private static void expectFeedback(SpeechCues speech, SpeechCues.TestStage stage, String guess) {
        var feedback = speech.takeTestFeedback();
        if (feedback == null || feedback.stage() != stage || !feedback.guess().equals(guess))
            throw new AssertionError("Unexpected local tester feedback for " + stage);
    }

    private static void checkFeedback(Path root) throws Exception {
        Files.createFile(root.resolve("test-feedback"));
        var cues = new LinkedBlockingQueue<Cue>();
        try (var speech = speech(cues::add)) {
            speech.setEnabled(true);
            await(() -> speech.status() == SpeechCues.Status.READY, 12);
            submit(speech);
            if (cues.poll(4, TimeUnit.SECONDS) != Cue.ENDERMAN || speech.takeTestFeedback() != null
                    || !Files.readString(root.resolve("request-1")).equals("False"))
                throw new AssertionError("An ordinary call requested tester data");
            speech.setTesting(true);
            byte[] voice = new byte[640];
            for (int i = 1; i < voice.length; i += 2) voice[i] = 8;
            speech.acceptFrame(voice);
            expectFeedback(speech, SpeechCues.TestStage.LISTENING, "");
            speech.endUtterance();
            await(() -> Files.exists(root.resolve("request-2")), 4);
            expectFeedback(speech, SpeechCues.TestStage.CHECKING, "");
            // The worker double returns a different name. The consumer must see exactly that choice.
            if (cues.poll(4, TimeUnit.SECONDS) != Cue.CREEPER) throw new AssertionError("Wrong-match decision was hidden");
            for (int n = 3; n <= 4; n++) {
                long before = speech.lastDecision().number();
                submit(speech);
                await(() -> speech.lastDecision().number() > before, 4);
                expectFeedback(speech, SpeechCues.TestStage.NO_MATCH, n == 3 ? "low bunny" : "");
                if (!cues.isEmpty()) throw new AssertionError("A rejected guess became a mob call");
            }
            speech.acceptFrame(new byte[640]);
            speech.endUtterance();
            if (speech.takeTestFeedback() != null) throw new AssertionError("Silence claimed a listening attempt");
            speech.acceptFrame(voice, false);
            speech.endUtterance();
            expectFeedback(speech, SpeechCues.TestStage.NO_VOICE, "");
            for (int i = 0; i < SpeechCues.Utterances.MAX_FRAMES + 10; i++) speech.acceptFrame(voice);
            speech.endUtterance();
            expectFeedback(speech, SpeechCues.TestStage.TOO_LONG, "");
            long before = speech.lastDecision().number();
            submit(speech);
            await(() -> Files.exists(root.resolve("request-5")), 4);
            speech.setTesting(false);
            speech.setTesting(true);
            await(() -> speech.lastDecision().number() > before, 4);
            if (speech.takeTestFeedback() != null || !cues.isEmpty())
                throw new AssertionError("A guess survived toggling tester mode");
            speech.setTesting(false);
            submit(speech);
            await(() -> Files.exists(root.resolve("request-6")) && speech.lastDecision().number() > before + 1, 4);
            if (!Files.readString(root.resolve("request-6")).equals("False") || speech.takeTestFeedback() != null)
                throw new AssertionError("The tester data channel stayed active");
            speech.setTesting(true);
            for (var issue : new SpeechCues.DecoderIssue[]{SpeechCues.DecoderIssue.ERROR, SpeechCues.DecoderIssue.TIMEOUT}) {
                long prior = speech.lastDecision().number();
                submit(speech);
                await(() -> speech.lastDecision().number() > prior, 4);
                if (speech.lastDecision().matched() || speech.lastDecision().issue() != issue || !cues.isEmpty())
                    throw new AssertionError("A local decoder fault became a mob call or a no-match");
                expectFeedback(speech, SpeechCues.TestStage.DECODER_ERROR, "");
            }
        }
        System.out.println("MOBSEAR_PRIVATE_FEEDBACK_PASS listening checking wrongChoice rejectedGuess emptyGuess quiet dropped long optOut");
    }

    public static void main(String[] args) throws Exception {
        if (!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) throw new IllegalStateException("Hosted only");
        Path root=Path.of(System.getenv("MOBSEAR_WORKER_FIXTURE"));
        var cues=new LinkedBlockingQueue<Cue>();
        try (var speech=speech(cues::add)) {
            speech.setEnabled(true);
            await(()->speech.status()==SpeechCues.Status.READY,10);
            submit(speech);
            await(()->Files.exists(root.resolve("late-returned")),15);
            if (cues.poll(300,TimeUnit.MILLISECONDS)!=null || speech.status()!=SpeechCues.Status.READY
                    || speech.lastDecision()==null || speech.lastDecision().timely())
                throw new AssertionError("A late call dispatched or closed the recognizer");
            submit(speech);
            if (cues.poll(4,TimeUnit.SECONDS)!=Cue.ENDERMAN) throw new AssertionError("The next fresh call was lost");
            Files.createFile(root.resolve("stall"));
            submit(speech);
            await(()->Files.exists(root.resolve("recovered")) && speech.status()==SpeechCues.Status.READY,36);
            if (!cues.isEmpty()) throw new AssertionError("A stalled call was replayed");
            submit(speech);
            if (cues.poll(4,TimeUnit.SECONDS)!=Cue.ENDERMAN) throw new AssertionError("Failed to recover the next fresh call");
            Files.createFile(root.resolve("stall"));
            submit(speech);
            await(()->speech.status()==SpeechCues.Status.UNAVAILABLE,32);
            if (!speech.decoderTimedOut() || !cues.isEmpty()) throw new AssertionError("Final timeout was not identified");
            System.out.println("MOBSEAR_WORKER_RECOVERY_PASS lateDropped=true nextFresh=true boundedRecovery=1 finalTimeout=true");
        }
        checkFeedback(root);
    }
}

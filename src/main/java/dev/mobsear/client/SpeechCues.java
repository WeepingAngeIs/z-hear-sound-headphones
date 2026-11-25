package dev.mobsear.client;

import dev.mobsear.client.speech.ModelStartup;

import dev.mobsear.net.Cue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Device-local recognition of opted-in calls supported by transmitted voice. */
public final class SpeechCues implements AutoCloseable {
    public enum Status { OFF, STARTING, READY, UNAVAILABLE }
    public enum DecoderIssue { NONE, ERROR, TIMEOUT }
    public record Decision(long number, boolean matched, boolean timely, long ageMillis, DecoderIssue issue) {}
    enum TestStage { LISTENING, CHECKING, NO_MATCH, LATE, TOO_LONG, NO_VOICE, DECODER_ERROR }
    record TestFeedback(long attempt, TestStage stage, String guess) {}

    private static final ScheduledThreadPoolExecutor TIMEOUTS = createTimeouts();
    private static final long MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(4);
    private static final long LIVE_SPECIES_RESULT_LIMIT = TimeUnit.MILLISECONDS.toNanos(4500);
    private final Consumer<Cue> listener;
    private final Consumer<String> speciesListener;
    private final Consumer<String> entityListener;
    private final Consumer<Process> configureWorker;
    @FunctionalInterface interface WorkerLauncher { Process start(boolean tracked) throws IOException; }
    private final WorkerLauncher launcher;
    private final Path aliasFile;
    private volatile List<String> nearbyNames = List.of();
    private volatile List<String> nearbySpecies = List.of();
    private SpeechGrammar.Registries catalogs;
    private Map<String, List<String>> entitySource = Map.of();
    private Session session;
    private Status status = Status.OFF;
    private boolean closed, inputPaused;
    private Decision lastDecision;
    private long decisionNumber;
    private boolean testing;
    private long nextAttempt, latestAttempt;
    private TestFeedback testFeedback;

    public SpeechCues(Consumer<Cue> listener) {
        this(listener, null);
    }

    public SpeechCues(Consumer<Cue> listener, Path aliasFile) {
        this(listener, id -> {}, aliasFile, Map.of());
    }

    public SpeechCues(Consumer<Cue> listener, Consumer<String> speciesListener, Path aliasFile,
                      Map<String, List<String>> speciesDisplayNames) {
        this(listener, speciesListener, id -> {}, aliasFile, speciesDisplayNames, Map.of());
    }

    public SpeechCues(Consumer<Cue> listener, Consumer<String> speciesListener, Consumer<String> entityListener,
                      Path aliasFile, Map<String, List<String>> speciesDisplayNames, Map<String, List<String>> entityDisplayNames) {
        this(listener, speciesListener, entityListener, aliasFile, speciesDisplayNames, entityDisplayNames, process -> {});
    }

    SpeechCues(Consumer<Cue> listener, Consumer<String> speciesListener, Consumer<String> entityListener,
               Path aliasFile, Map<String, List<String>> speciesDisplayNames, Map<String, List<String>> entityDisplayNames,
               Consumer<Process> configureWorker) {
        this(listener,speciesListener,entityListener,aliasFile,speciesDisplayNames,entityDisplayNames,configureWorker,WindowsSpeechWorker::launch);
    }

    SpeechCues(Consumer<Cue> listener, Consumer<String> speciesListener, Consumer<String> entityListener,
               Path aliasFile, Map<String, List<String>> speciesDisplayNames, Map<String, List<String>> entityDisplayNames,
               Consumer<Process> configureWorker, WorkerLauncher launcher) {
        this.launcher = Objects.requireNonNull(launcher);
        this.listener = Objects.requireNonNull(listener);
        this.speciesListener = Objects.requireNonNull(speciesListener);
        this.entityListener = Objects.requireNonNull(entityListener);
        this.configureWorker = Objects.requireNonNull(configureWorker);
        this.aliasFile = aliasFile;
        refreshCatalog(speciesDisplayNames, entityDisplayNames);
    }

    /** English desktop SAPI uses the registry's English canonical/display names, never an entity nickname. */
    public synchronized void refreshSpecies(Map<String, List<String>> speciesDisplayNames) {
        refreshCatalog(speciesDisplayNames, entitySource);
    }

    public synchronized void refreshCatalog(Map<String, List<String>> speciesDisplayNames, Map<String, List<String>> entityDisplayNames) {
        if (closed) return;
        if (entityDisplayNames != entitySource) {
            var snapshot = new java.util.HashMap<String, List<String>>();
            if (entityDisplayNames.size() <= 4096) entityDisplayNames.forEach((key, labels) -> {
                if (key != null && labels != null) snapshot.put(key, labels.stream().limit(6).filter(Objects::nonNull).toList());
            });
            entitySource = Map.copyOf(snapshot);
        }
        var updated = SpeechGrammar.registries(speciesDisplayNames, entitySource);
        if (updated.equals(catalogs)) return;
        catalogs = updated;
        if (session != null) {
            setEnabled(false);
            setEnabled(true);
        }
    }

    public synchronized Status status() {
        return status;
    }

    /** Transient local timing only. No phrase, audio or identifier is retained here. */
    public synchronized Decision lastDecision() { return lastDecision; }

    void setNearbyNames(List<String> names) { nearbyNames = List.copyOf(names); }
    void setNearbySpecies(List<String> ids) { nearbySpecies = List.copyOf(ids); }

    // A recent server roster can corroborate a slightly delayed species result. Queue admission and
    // the server's independent five-second transmission window still apply.
    static boolean resultIsTimely(long age, boolean liveSpecies) {
        return age <= (liveSpecies ? LIVE_SPECIES_RESULT_LIMIT : MAX_AGE_NANOS);
    }

    synchronized void setTesting(boolean enabled) {
        if (testing == enabled) return;
        testing = enabled;
        clearTest();
    }

    /** The game thread consumes the local hint once. A subsequent word supersedes it. */
    synchronized TestFeedback takeTestFeedback() {
        var result = testFeedback;
        testFeedback = null;
        return result;
    }

    private void clearTest() {
        latestAttempt = 0;
        testFeedback = null;
    }

    private boolean currentTest(long attempt) {
        return testing && attempt != 0 && attempt == latestAttempt;
    }

    private void reportTest(long attempt, TestStage stage, String guess) {
        if (currentTest(attempt)) testFeedback = new TestFeedback(attempt, stage, guess);
    }

    public synchronized ModelStartup modelStartupIssue() {
        return desktopOnly() ? session.modelIssue : null;
    }

    public synchronized boolean desktopOnly() {
        return status == Status.READY && session != null && session.desktopOnly;
    }

    public synchronized boolean startupTimedOut() {
        return status == Status.UNAVAILABLE && session != null && session.startupExpired;
    }

    public synchronized boolean decoderTimedOut() {
        return status == Status.UNAVAILABLE && session != null && session.decodeExpired;
    }

    public synchronized void setEnabled(boolean enabled) {
        if (!enabled) {
            Session previous = session;
            session = null;
            status = Status.OFF;
            lastDecision = null;
            clearTest();
            if (previous != null) previous.stop();
        } else if (!closed && status == Status.OFF) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                status = Status.UNAVAILABLE;
                return;
            }
            startSession(false);
        }
    }

    private void startSession(boolean recovering) {
        status = Status.STARTING;
        session = new Session(recovering);
        session.worker.start();
    }

    /** Called only with the local speaker's 20 ms PCM16 mono frames at 16 kHz. */
    public void acceptFrame(byte[] pcm) {
        acceptFrame(pcm, true);
    }

    public synchronized void acceptFrame(byte[] pcm, boolean queuedForVoice) {
        if (!inputPaused && status == Status.READY && pcm.length == Utterances.FRAME_BYTES) {
            session.utterances.accept(pcm, queuedForVoice);
        }
    }

    /** Flushes the final short phrase even if the player releases PTT mid-frame. */
    public synchronized void endUtterance() {
        if (!inputPaused && status == Status.READY) session.utterances.finish();
    }

    /** Opening a screen silences input immediately while preserving the loaded registry. */
    public synchronized void setInputPaused(boolean paused) {
        if (inputPaused == paused) return;
        inputPaused = paused;
        lastDecision = null;
        clearTest();
        if (session != null) {
            session.inputRevision++;
            session.utterances.clear();
            session.clearPending();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        setEnabled(false);
    }

    private static ScheduledThreadPoolExecutor createTimeouts() {
        var executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("Mob's Ear speech timeout").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static Process launch() throws IOException {
        return launch(false);
    }

    private static Process launch(boolean trackedStartup) throws IOException {
        return WindowsSpeechWorker.launch(trackedStartup);
    }

    private record Clip(byte[] pcm, long endedAt, long inputRevision, long testAttempt) {
        boolean fresh() {
            return System.nanoTime() - endedAt <= MAX_AGE_NANOS;
        }

        void clear() {
            Arrays.fill(pcm, (byte) 0);
        }
    }

    private record Reply(int code, String guess) {}

    private final class Session {
        private final SpeechGrammar.Registries registry = catalogs;
        private final ArrayBlockingQueue<Clip> pending = new ArrayBlockingQueue<>(1);
        private long activeAttempt;
        private final Utterances utterances = new Utterances(this::submit, this::beginUtterance,
                reason -> reportTest(activeAttempt, reason, ""));
        private final Thread worker = Thread.ofPlatform().daemon().name("Mob's Ear speech")
                .unstarted(this::run);
        private Process helper;
        private long inputRevision;
        private volatile int startupStep;
        private volatile boolean startupExpired;
        private boolean desktopOnly;
        private ModelStartup modelIssue;
        private volatile boolean decodeExpired;
        private final boolean recovering;
        private List<String> sentNames = List.of();
        private List<String> sentSpecies = List.of();

        Session(boolean recovering) {
            this.recovering = recovering;
        }

        private void beginUtterance() {
            activeAttempt = testing ? ++nextAttempt : 0;
            latestAttempt = activeAttempt;
            reportTest(activeAttempt, TestStage.LISTENING, "");
        }

        // Called by the capture thread under the owner's monitor. It never waits for SAPI.
        private void submit(byte[] pcm) {
            clearPending();
            pending.offer(new Clip(pcm, System.nanoTime(), inputRevision, activeAttempt));
        }

        private void clearPending() {
            Clip old = pending.poll();
            if (old != null) old.clear();
        }

        private void stop() {
            utterances.clear();
            clearPending();
            worker.interrupt();
            if (helper != null) helper.toHandle().destroyForcibly();
        }

        private void run() {
            Process child = null;
            try {
                var grammar = new SpeechGrammar(aliasFile, registry);
                child = launcher.start(true);
                synchronized (SpeechCues.this) {
                    if (session != this) return;
                    helper = child;
                }
                configureWorker.accept(child);
                try (var input = new DataInputStream(child.getInputStream());
                     var progress = new DataInputStream(child.getErrorStream());
                     var output = new DataOutputStream(child.getOutputStream())) {
                    prepare(child, progress, input, output, grammar);
                    synchronized (SpeechCues.this) {
                        if (session != this) return;
                        status = Status.READY;
                    }
                    while (!Thread.currentThread().isInterrupted()) {
                        Clip clip = pending.take();
                        try {
                            boolean detail;
                            synchronized (SpeechCues.this) {
                                if (session != this || inputPaused || clip.inputRevision != inputRevision) continue;
                                if (!clip.fresh()) {
                                    reportTest(clip.testAttempt, TestStage.LATE, "");
                                    continue;
                                }
                                detail = currentTest(clip.testAttempt);
                                reportTest(clip.testAttempt, TestStage.CHECKING, "");
                            }
                            var reply = exchange(child, input, output, clip, detail, grammar);
                            synchronized (SpeechCues.this) {
                                if (session == this && !inputPaused && clip.inputRevision == inputRevision) {
                                    long age = System.nanoTime() - clip.endedAt;
                                    DecoderIssue issue = switch (reply.code) {
                                        case 65534 -> DecoderIssue.ERROR;
                                        case 65535 -> DecoderIssue.TIMEOUT;
                                        default -> DecoderIssue.NONE;
                                    };
                                    int decision = issue == DecoderIssue.NONE ? reply.code : 0;
                                    String id = grammar.speciesId(decision & ~SpeechGrammar.NEARBY_GUESS);
                                    boolean liveSpecies = id != null && nearbySpecies.contains(id);
                                    if ((decision & SpeechGrammar.NEARBY_GUESS) != 0 && !liveSpecies) decision = 0;
                                    boolean timely = resultIsTimely(age, liveSpecies);
                                    if (timely && !grammar.dispatch(decision, listener, speciesListener, entityListener))
                                        throw new IOException("Invalid speech helper reply");
                                    lastDecision = new Decision(++decisionNumber, decision != 0, timely,
                                            TimeUnit.NANOSECONDS.toMillis(age), issue);
                                    if (!timely) reportTest(clip.testAttempt, TestStage.LATE, "");
                                    else if (issue != DecoderIssue.NONE) reportTest(clip.testAttempt, TestStage.DECODER_ERROR, "");
                                    else if (decision == 0) reportTest(clip.testAttempt, TestStage.NO_MATCH, reply.guess);
                                    else if (currentTest(clip.testAttempt)) testFeedback = null; // The matched call supplies its display name.
                                }
                            }
                        } finally {
                            clip.clear();
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException ignored) {
                // Fixed status is user-visible; exception text or speech data never enters logs.
            } finally {
                if (child != null) child.toHandle().destroyForcibly();
                synchronized (SpeechCues.this) {
                    if (session == this) {
                        utterances.clear();
                        clearPending();
                        helper = null;
                        clearTest();
                        // A stalled clip is never replayed. A fresh helper may serve the next call.
                        if (decodeExpired && !recovering && !closed) startSession(true);
                        else status = Status.UNAVAILABLE;
                    }
                }
            }
        }

        private void prepare(Process child, DataInputStream progress, DataInputStream input,
                             DataOutputStream output, SpeechGrammar grammar)
                throws IOException {
            long limit = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            // Wait for the reader, catalog, desktop engine, loaded grammar and warmed decoder in order.
            for (int next : new int[]{81, 71, 69, 76, 82}) {
                long remaining = Math.min(TimeUnit.SECONDS.toNanos(90), limit - System.nanoTime());
                if (remaining <= 0) throw new IOException("Speech startup expired");
                var timeout = TIMEOUTS.schedule(() -> {
                    startupExpired = true;
                    child.toHandle().destroyForcibly();
                }, remaining, TimeUnit.NANOSECONDS);
                try {
                    if (startupStep == 81) grammar.writeTo(output);
                    var channel = next == 82 ? input : progress;
                    int actual = channel.readUnsignedByte();
                    if (next == 82 && actual == 100) {
                        modelIssue = ModelStartup.read(input);
                        desktopOnly = true;
                    } else if (actual == next) {
                        if (next == 82) { desktopOnly = false; modelIssue = null; }
                    } else if (next == 82 && actual == 68) {
                        desktopOnly = true;
                        modelIssue = null;
                    } else throw new IOException("Unexpected speech startup stage");
                    startupStep = next;
                } finally {
                    timeout.cancel(false);
                }
            }
        }

        private Reply exchange(Process child, DataInputStream input, DataOutputStream output, Clip clip, boolean detail, SpeechGrammar grammar)
                throws IOException {
            // The normal short freshness limit still applies. Give the engine time to finish
            // the discarded clip before treating it as unresponsive and recycling the worker.
            var deadline = TIMEOUTS.schedule(() -> {
                decodeExpired = true;
                child.toHandle().destroyForcibly();
            }, 24, TimeUnit.SECONDS);
            try {
                var hints = nearbyNames;
                var eligible = nearbySpecies;
                if (!sentNames.equals(hints) || !sentSpecies.equals(eligible)) {
                    var rows = new StringBuilder(String.join("\n", hints));
                    for (String label : grammar.nearbyLabels(eligible)) rows.append("\n+").append(label);
                    byte[] names = rows.toString().getBytes(StandardCharsets.US_ASCII);
                    output.writeInt(0);
                    output.writeInt(Integer.reverseBytes(names.length));
                    output.write(names);
                    sentNames = hints;
                    sentSpecies = eligible;
                }
                // A negative length requests a one-shot hint on the device-local pipe.
                output.writeInt(Integer.reverseBytes(detail ? -clip.pcm.length : clip.pcm.length));
                output.write(clip.pcm);
                output.flush();
                int code = input.readUnsignedShort();
                if (!detail) return new Reply(code, "");
                int size = input.readUnsignedByte();
                if (size > 96) throw new IOException("Invalid local hint size");
                byte[] hint = new byte[size];
                try {
                    input.readFully(hint);
                    return new Reply(code, new String(hint, StandardCharsets.UTF_8));
                } finally {
                    Arrays.fill(hint, (byte) 0);
                }
            } finally {
                deadline.cancel(false);
            }
        }
    }

    /** Split connected speech at a quiet word boundary. Never send a clip cut through sustained sound. */
    static final class Utterances {
        static final int FRAME_BYTES = 640;
        private static final int END_SILENCE_FRAMES = 18;
        private static final int DECODER_TAIL_FRAMES = 28;
        private static final int LONG_SPEECH_FRAMES = 285;
        static final int MAX_FRAMES = 400;
        private final byte[] current = new byte[MAX_FRAMES * FRAME_BYTES];
        // Preserve quiet initial consonants and give the desktop decoder a stable noise floor.
        private final byte[] preRoll = new byte[10 * FRAME_BYTES];
        private final Consumer<byte[]> output;
        private final Runnable onStart;
        private final Consumer<TestStage> onSkip;
        private int used;
        private int silenceFrames;
        private boolean tooLong;
        private boolean sentVoice;

        Utterances(Consumer<byte[]> output) {
            this(output, () -> {}, reason -> {});
        }

        Utterances(Consumer<byte[]> output, Runnable onStart, Consumer<TestStage> onSkip) {
            this.output = output;
            this.onStart = onStart;
            this.onSkip = onSkip;
        }

        void accept(byte[] frame) {
            accept(frame, true);
        }

        void accept(byte[] frame, boolean queuedForVoice) {
            boolean voiced = hasEnergy(frame);
            if (voiced && used == 0 && !tooLong) onStart.run();
            sentVoice |= voiced && queuedForVoice;
            if (!voiced && used == 0 && !tooLong) {
                System.arraycopy(preRoll, FRAME_BYTES, preRoll, 0, preRoll.length - FRAME_BYTES);
                System.arraycopy(frame, 0, preRoll, preRoll.length - FRAME_BYTES, FRAME_BYTES);
                return;
            }
            silenceFrames = voiced ? 0 : silenceFrames + 1;
            if (!tooLong) {
                if (used == 0) {
                    System.arraycopy(preRoll, 0, current, 0, preRoll.length);
                    used = preRoll.length;
                }
                if (used == current.length) {
                    Arrays.fill(current, (byte) 0);
                    used = 0;
                    tooLong = true;
                    onSkip.accept(TestStage.TOO_LONG);
                } else {
                    System.arraycopy(frame, 0, current, used, FRAME_BYTES);
                    used += FRAME_BYTES;
                }
            }
            if (silenceFrames >= END_SILENCE_FRAMES || (silenceFrames >= 4 && (tooLong || used >= LONG_SPEECH_FRAMES * FRAME_BYTES))) finish();
        }

        private static boolean hasEnergy(byte[] frame) {
            long energy = 0;
            for (int i = 0; i < frame.length; i += 2) {
                int sample = (frame[i] & 0xff) | (frame[i + 1] << 8);
                energy += (long) sample * sample;
            }
            return energy > 128L * 128 * (FRAME_BYTES / 2);
        }

        void finish() {
            // A crowded game tick may drop network frames. Decode the intact word only if
            // this actual utterance also contributed voiced audio to proximity chat.
            if (used > 0 && !tooLong && sentVoice) {
                // Append synthetic silence in memory without delaying the PTT-release endpoint.
                int padding = (DECODER_TAIL_FRAMES - silenceFrames) * FRAME_BYTES;
                output.accept(Arrays.copyOf(current, Math.min(current.length, used + padding)));
            } else if (used > 0 && !tooLong) {
                onSkip.accept(TestStage.NO_VOICE);
            }
            clear();
        }

        void clear() {
            Arrays.fill(current, 0, used, (byte) 0);
            Arrays.fill(preRoll, (byte) 0);
            used = 0;
            silenceFrames = 0;
            tooLong = false;
            sentVoice = false;
        }
    }
}

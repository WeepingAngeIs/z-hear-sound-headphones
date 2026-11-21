package dev.mobsear.client;

import dev.mobsear.net.Cue;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Opt-in, device-local recognition of short calls, using only transmitted PCM. */
public final class SpeechCues implements AutoCloseable {
    public enum Status { OFF, STARTING, READY, UNAVAILABLE }

    private static final ScheduledThreadPoolExecutor TIMEOUTS = createTimeouts();
    private static final long MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(4);
    private final Consumer<Cue> listener;
    private Session session;
    private Status status = Status.OFF;
    private boolean closed;

    public SpeechCues(Consumer<Cue> listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized void setEnabled(boolean enabled) {
        if (!enabled) {
            Session previous = session;
            session = null;
            status = Status.OFF;
            if (previous != null) previous.stop();
        } else if (!closed && status == Status.OFF) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                status = Status.UNAVAILABLE;
                return;
            }
            status = Status.STARTING;
            session = new Session();
            session.worker.start();
        }
    }

    /** Called only with the local speaker's 20 ms PCM16 mono frames at 16 kHz. */
    public synchronized void acceptFrame(byte[] pcm) {
        if (status == Status.READY && pcm.length == Utterances.FRAME_BYTES) {
            session.utterances.accept(pcm);
        }
    }

    /** Flushes the final short phrase even if the player releases PTT mid-frame. */
    public synchronized void endUtterance() {
        if (status == Status.READY) session.utterances.finish();
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
        String windows = System.getenv("SystemRoot");
        if (windows == null) throw new IOException("Missing Windows system directory");
        Path shell = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        try (InputStream source = SpeechCues.class.getResourceAsStream("recognize.ps1")) {
            if (source == null) throw new IOException("Missing speech helper");
            // The command contains only the bundled helper. All PCM travels in a binary pipe.
            String helper = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            return new ProcessBuilder(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command", helper)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        }
    }

    private record Clip(byte[] pcm, long endedAt) {
        boolean fresh() {
            return System.nanoTime() - endedAt <= MAX_AGE_NANOS;
        }

        void clear() {
            Arrays.fill(pcm, (byte) 0);
        }
    }

    private final class Session {
        private final ArrayBlockingQueue<Clip> pending = new ArrayBlockingQueue<>(1);
        private final Utterances utterances = new Utterances(this::submit);
        private final Thread worker = Thread.ofPlatform().daemon().name("Mob's Ear speech")
                .unstarted(this::run);
        private Process helper;

        // Called by the capture thread under the owner's monitor. It never waits for SAPI.
        private void submit(byte[] pcm) {
            clearPending();
            pending.offer(new Clip(pcm, System.nanoTime()));
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
                child = launch();
                synchronized (SpeechCues.this) {
                    if (session != this) return;
                    helper = child;
                }
                try (var input = child.getInputStream();
                     var output = new DataOutputStream(child.getOutputStream())) {
                    if (exchange(child, input, output, null) != 82) throw new IOException();
                    synchronized (SpeechCues.this) {
                        if (session != this) return;
                        status = Status.READY;
                    }
                    while (!Thread.currentThread().isInterrupted()) {
                        Clip clip = pending.take();
                        try {
                            if (!clip.fresh()) continue;
                            int decision = exchange(child, input, output, clip);
                            Cue cue = switch (decision) {
                                case 0 -> null;
                                case 1 -> Cue.ZOMBIE;
                                case 2 -> Cue.MOBS;
                                case 3 -> Cue.ENDERMAN;
                                default -> throw new IOException("Invalid speech helper reply");
                            };
                            synchronized (SpeechCues.this) {
                                if (session == this && cue != null && clip.fresh()) listener.accept(cue);
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
                        status = Status.UNAVAILABLE;
                    }
                }
            }
        }

        private int exchange(Process child, InputStream input, DataOutputStream output, Clip clip)
                throws IOException {
            var deadline = TIMEOUTS.schedule(() -> { child.toHandle().destroyForcibly(); },
                    clip == null ? 20 : 8, TimeUnit.SECONDS);
            try {
                if (clip != null) {
                    output.writeInt(Integer.reverseBytes(clip.pcm.length));
                    output.write(clip.pcm);
                    output.flush();
                }
                int reply = input.read();
                if (reply < 0) throw new EOFException();
                return reply;
            } finally {
                deadline.cancel(false);
            }
        }
    }

    /** A short silence ends a call. Continuous, over-length speech is dropped as a unit. */
    static final class Utterances {
        static final int FRAME_BYTES = 640;
        private static final int END_SILENCE_FRAMES = 18;
        private static final int DECODER_TAIL_FRAMES = 28;
        private final byte[] current = new byte[150 * FRAME_BYTES];
        // Preserve quiet initial consonants and give the desktop decoder a stable noise floor.
        private final byte[] preRoll = new byte[10 * FRAME_BYTES];
        private final Consumer<byte[]> output;
        private int used;
        private int silenceFrames;
        private boolean tooLong;

        Utterances(Consumer<byte[]> output) {
            this.output = output;
        }

        void accept(byte[] frame) {
            boolean voiced = hasEnergy(frame);
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
                } else {
                    System.arraycopy(frame, 0, current, used, FRAME_BYTES);
                    used += FRAME_BYTES;
                }
            }
            if (silenceFrames >= END_SILENCE_FRAMES) finish();
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
            if (used > 0 && !tooLong) {
                // Append synthetic silence in memory without delaying the PTT-release endpoint.
                int padding = (DECODER_TAIL_FRAMES - silenceFrames) * FRAME_BYTES;
                output.accept(Arrays.copyOf(current, Math.min(current.length, used + padding)));
            }
            clear();
        }

        void clear() {
            Arrays.fill(current, 0, used, (byte) 0);
            Arrays.fill(preRoll, (byte) 0);
            used = 0;
            silenceFrames = 0;
            tooLong = false;
        }
    }
}

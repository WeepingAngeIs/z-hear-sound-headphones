package dev.mobsear.client;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** A connection's default-device voice session. Construction opens no devices. */
public final class VoiceAudio implements AutoCloseable {
    public static final int SAMPLE_RATE = 16_000;
    public static final int FRAME_BYTES = 640;
    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long OUTPUT_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    public enum Problem { MICROPHONE_UNAVAILABLE, OUTPUT_UNAVAILABLE }

    @FunctionalInterface
    interface LineFactory<T> {
        T get(AudioFormat format) throws LineUnavailableException;
    }

    private final Consumer<byte[]> onCapturedFrame;
    private final Consumer<Problem> onProblem;
    private final LineFactory<TargetDataLine> microphoneFactory;
    private final LineFactory<SourceDataLine> speakerFactory;
    private final FrameMixer mixer = new FrameMixer();
    private boolean closed;
    private boolean transmitting;
    private long captureGeneration;
    private Thread inputWorker;
    private Thread outputWorker;
    private SourceDataLine outputLine;
    private boolean outputFailed;
    private long outputFailedAt;

    /** Callbacks run on audio workers. The frame consumer receives ownership of each frame. */
    public VoiceAudio(Consumer<byte[]> onCapturedFrame, Consumer<Problem> onProblem) {
        this(onCapturedFrame, onProblem, AudioSystem::getTargetDataLine, AudioSystem::getSourceDataLine);
    }

    VoiceAudio(Consumer<byte[]> onCapturedFrame, Consumer<Problem> onProblem,
               LineFactory<TargetDataLine> microphoneFactory, LineFactory<SourceDataLine> speakerFactory) {
        this.onCapturedFrame = Objects.requireNonNull(onCapturedFrame);
        this.onProblem = Objects.requireNonNull(onProblem);
        this.microphoneFactory = Objects.requireNonNull(microphoneFactory);
        this.speakerFactory = Objects.requireNonNull(speakerFactory);
    }

    public synchronized void setTransmitting(boolean held) {
        if (closed || transmitting == held) {
            return;
        }
        transmitting = held;
        captureGeneration++;
        if (held && inputWorker == null) {
            inputWorker = Thread.ofPlatform().daemon().name("Mobs Ear microphone").start(this::captureLoop);
        }
        notifyAll();
    }

    public synchronized void play(UUID speaker, byte[] samples, double gain) {
        if (closed || (outputFailed && System.nanoTime() - outputFailedAt < OUTPUT_RETRY_NANOS)) {
            return;
        }
        if (mixer.offer(speaker, samples, gain)) {
            if (outputWorker == null) {
                outputWorker = Thread.ofPlatform().daemon().name("Mobs Ear playback").start(this::outputLoop);
            }
            notifyAll();
        }
    }

    private void captureLoop() {
        long lastGeneration = 0;
        try {
            while (true) {
                synchronized (this) {
                    while (!closed && (!transmitting || lastGeneration == captureGeneration)) {
                        wait();
                    }
                    if (closed) {
                        return;
                    }
                    lastGeneration = captureGeneration;
                }
                try {
                    capturePress(lastGeneration);
                } catch (LineUnavailableException | RuntimeException e) {
                    if (currentPress(lastGeneration)) {
                        onProblem.accept(Problem.MICROPHONE_UNAVAILABLE);
                    }
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private void capturePress(long generation) throws LineUnavailableException, InterruptedException {
        try (TargetDataLine microphone = microphoneFactory.get(FORMAT)) {
            microphone.open(FORMAT, 4 * FRAME_BYTES);
            if (!currentPress(generation)) {
                return;
            }
            microphone.start();
            while (currentPress(generation)) {
                // Reading only a complete, already available frame keeps release responsive.
                if (microphone.available() < FRAME_BYTES) {
                    synchronized (this) {
                        if (currentPress(generation)) {
                            wait(5);
                        }
                    }
                    continue;
                }
                byte[] frame = new byte[FRAME_BYTES];
                int count = microphone.read(frame, 0, frame.length);
                if (count == frame.length && currentPress(generation)) {
                    onCapturedFrame.accept(frame);
                }
            }
        }
    }

    private synchronized boolean currentPress(long generation) {
        return !closed && transmitting && captureGeneration == generation;
    }

    private void outputLoop() {
        boolean reportFailure = false;
        try (SourceDataLine output = speakerFactory.get(FORMAT)) {
            output.open(FORMAT, 3 * FRAME_BYTES);
            synchronized (this) {
                if (closed) {
                    return;
                }
                outputLine = output;
                outputFailed = false;
            }
            output.start();
            pumpOutput(output);
        } catch (LineUnavailableException | RuntimeException e) {
            reportFailure = true;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                outputWorker = null;
                outputLine = null;
                mixer.clear();
                reportFailure &= !closed;
                if (reportFailure) {
                    outputFailed = true;
                    outputFailedAt = System.nanoTime();
                }
            }
            if (reportFailure) {
                onProblem.accept(Problem.OUTPUT_UNAVAILABLE);
            }
        }
    }

    private void pumpOutput(SourceDataLine output) throws InterruptedException {
        long nextFrame = System.nanoTime() + 2 * FRAME_NANOS;
        while (true) {
            byte[] mixed;
            synchronized (this) {
                while (!closed && mixer.isEmpty()) {
                    wait();
                }
                if (closed) {
                    return;
                }
                long now = System.nanoTime();
                if (now - nextFrame > 5 * FRAME_NANOS) {
                    nextFrame = now + 2 * FRAME_NANOS;
                }
                long remaining;
                while (!closed && (remaining = nextFrame - System.nanoTime()) > 0) {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
                if (closed) {
                    return;
                }
                mixed = mixer.mixNext();
                nextFrame = Math.max(nextFrame + FRAME_NANOS, System.nanoTime());
            }
            for (int offset = 0; offset < mixed.length;) {
                int written = output.write(mixed, offset, mixed.length - offset);
                if (written <= 0) {
                    return;
                }
                offset += written;
            }
        }
    }

    @Override
    public void close() {
        SourceDataLine line;
        synchronized (this) {
            closed = true;
            transmitting = false;
            mixer.clear();
            line = outputLine;
            notifyAll();
        }
        if (line != null) {
            line.close();
        }
    }
}

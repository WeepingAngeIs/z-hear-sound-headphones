package dev.mobsear.client;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** Deterministic PCM fixture. No sound device, Minecraft process, or speech sample is opened. */
public final class VoiceAudioFixture {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    public static void main(String[] args) throws InterruptedException {
        var mixer = new FrameMixer();
        byte[] near = constant(-12_000);
        check(mixer.offer(FIRST, near, 0.25), "first speaker");
        check(mixer.offer(SECOND, constant(9_001), 0.5), "second speaker");
        near[0] = 0; // Mutating a received packet cannot change a queued frame.
        assertFrame(mixer.mixNext(), 1_501);
        check(mixer.isEmpty(), "queues released");

        for (int sign : new int[] {-1, 1}) {
            mixer.offer(FIRST, constant(sign * 31_000), 1);
            mixer.offer(SECOND, constant(sign * 31_000), 1);
            assertFrame(mixer.mixNext(), sign < 0 ? Short.MIN_VALUE : Short.MAX_VALUE);
        }

        for (int i = 0; i <= FrameMixer.MAX_FRAMES_PER_SPEAKER; i++) {
            mixer.offer(FIRST, constant(i), 1);
        }
        for (int i = 1; i <= FrameMixer.MAX_FRAMES_PER_SPEAKER; i++) {
            assertFrame(mixer.mixNext(), i);
        }
        check(mixer.isEmpty(), "latency bound");
        check(!mixer.offer(FIRST, new byte[1], 1), "partial frame");
        check(!mixer.offer(FIRST, constant(100), Double.NaN), "invalid gain");
        mixer.offer(FIRST, constant(100), 1);
        check(!mixer.offer(FIRST, constant(100), 0) && mixer.isEmpty(), "leave hearing range");
        for (int i = 0; i < FrameMixer.MAX_SPEAKERS; i++) {
            check(mixer.offer(new UUID(1, i), constant(1), 1), "admit voice");
        }
        check(!mixer.offer(FIRST, constant(1), 1), "simultaneous speaker bound");
        assertFrame(mixer.mixNext(), FrameMixer.MAX_SPEAKERS);

        exerciseSyntheticDevices();
        System.out.println("Audio fixture passed");
    }

    private static void exerciseSyntheticDevices() throws InterruptedException {
        byte[] source = constant(-8_000);
        var captured = new ArrayBlockingQueue<byte[]>(1);
        var played = new ArrayBlockingQueue<byte[]>(1);
        var failures = new ArrayBlockingQueue<VoiceAudio.Problem>(4);
        var microphoneClosed = new CountDownLatch(1);
        var speakerClosed = new CountDownLatch(1);
        var inputOpens = new AtomicInteger();
        var outputOpens = new AtomicInteger();
        var microphoneStarted = new AtomicBoolean();
        var microphoneHasFrame = new AtomicBoolean(true);
        var session = new VoiceAudio(captured::add, failures::add, format -> {
            checkFormat(format);
            inputOpens.incrementAndGet();
            return (TargetDataLine) Proxy.newProxyInstance(VoiceAudioFixture.class.getClassLoader(),
                    new Class<?>[] { TargetDataLine.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "open" -> null;
                        case "start" -> { microphoneStarted.set(true); yield null; }
                        case "available" -> microphoneStarted.get() && microphoneHasFrame.get() ? source.length : 0;
                        case "read" -> {
                            check(microphoneHasFrame.getAndSet(false), "single synthetic frame");
                            int length = (int) args[2];
                            System.arraycopy(source, 0, (byte[]) args[0], (int) args[1], length);
                            yield length;
                        }
                        case "close" -> { microphoneClosed.countDown(); yield null; }
                        default -> throw new IllegalStateException(method.getName());
                    });
        }, format -> {
            checkFormat(format);
            outputOpens.incrementAndGet();
            return (SourceDataLine) Proxy.newProxyInstance(VoiceAudioFixture.class.getClassLoader(),
                    new Class<?>[] { SourceDataLine.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "open", "start" -> null;
                        case "write" -> {
                            int offset = (int) args[1];
                            int length = (int) args[2];
                            played.add(Arrays.copyOfRange((byte[]) args[0], offset, offset + length));
                            yield length;
                        }
                        case "close" -> { speakerClosed.countDown(); yield null; }
                        default -> throw new IllegalStateException(method.getName());
                    });
        });
        try (session) {
            session.setTransmitting(false);
            session.play(FIRST, new byte[1], 1);
            check(inputOpens.get() == 0 && outputOpens.get() == 0, "inert before valid audio");
            session.setTransmitting(true);
            session.setTransmitting(true);
            byte[] wire = captured.poll(5, TimeUnit.SECONDS);
            check(Arrays.equals(source, wire), "PTT delivers complete signed little-endian samples");
            session.setTransmitting(false);
            check(microphoneClosed.await(5, TimeUnit.SECONDS), "release closes input");
            session.play(FIRST, wire, 0.25);
            byte[] sound = played.poll(5, TimeUnit.SECONDS);
            check(sound != null, "playback worker wrote a frame");
            assertFrame(sound, -2_000);
        }
        check(speakerClosed.await(5, TimeUnit.SECONDS), "session close releases output");
        session.setTransmitting(true);
        session.play(FIRST, constant(123), 1);
        check(inputOpens.get() == 1 && outputOpens.get() == 1 && failures.isEmpty(), "closed session stays closed");
    }

    private static void checkFormat(AudioFormat format) {
        check(format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) && format.getChannels() == 1
                && format.getSampleRate() == 16_000 && format.getSampleSizeInBits() == 16
                && !format.isBigEndian(), "fixture and wire use the same PCM format");
    }

    private static byte[] constant(int sample) {
        byte[] frame = new byte[VoiceAudio.FRAME_BYTES];
        for (int i = 0; i < frame.length; i += 2) {
            frame[i] = (byte) sample;
            frame[i + 1] = (byte) (sample >> 8);
        }
        return frame;
    }

    private static void assertFrame(byte[] frame, int expected) {
        check(frame.length == VoiceAudio.FRAME_BYTES, "frame size");
        for (int i = 0; i < frame.length; i += 2) {
            int actual = (frame[i] & 0xff) | (frame[i + 1] << 8);
            check(actual == expected, "mixed sample equals " + expected + ", got " + actual);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}

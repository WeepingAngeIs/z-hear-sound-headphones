package dev.mobsear.client;

import dev.mobsear.net.Cue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Standalone Java 21 checks. All inputs are generated in memory; no audio device is opened. */
public final class SpeechCuesFixture {
    private static final int FRAME = SpeechCues.Utterances.FRAME_BYTES;

    public static void main(String[] args) throws Exception {
        checkSegmentation();
        var received = new ArrayBlockingQueue<Cue>(8);
        try (var speech = new SpeechCues(received::add)) {
            require(speech.status() == SpeechCues.Status.OFF, "Recognition default");
            speech.acceptFrame(toneFrame());
            speech.endUtterance();
            require(received.isEmpty(), "Off state emitted a cue");
            if (args.length == 1 && args[0].equals("--windows-speech")) {
                checkWindows(speech, received);
            } else if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
                speech.setEnabled(true);
                require(speech.status() == SpeechCues.Status.UNAVAILABLE, "Unsupported host status");
            }
        }
        System.out.println("Speech fixture passed.");
    }

    private static void checkSegmentation() {
        var clips = new ArrayList<byte[]>();
        var segments = new SpeechCues.Utterances(clips::add);
        byte[] silence = new byte[FRAME];
        byte[] signal = toneFrame();
        for (int i = 0; i < 250; i++) segments.accept(silence);
        segments.finish();
        require(clips.isEmpty(), "Silence was queued");
        segments.accept(signal);
        for (int i = 0; i < 18; i++) segments.accept(silence);
        require(clips.size() == 1 && clips.getFirst().length == 22 * FRAME, "Silence endpoint");
        require(Arrays.equals(clips.getFirst(), 3 * FRAME, 4 * FRAME, signal, 0, FRAME), "PCM changed");
        segments.finish();
        require(clips.size() == 1, "Release duplicated a cue");
        for (int i = 0; i < 600; i++) segments.accept(signal);
        segments.finish();
        require(clips.size() == 1, "Long speech emitted a partial command");
        segments.accept(signal);
        segments.finish();
        require(clips.size() == 2, "Short call after over-length speech");
    }

    private static byte[] toneFrame() {
        byte[] signal = new byte[FRAME];
        for (int i = 0; i < FRAME / 2; i++) {
            short sample = (short) (4096.0 * Math.sin(i * 2.0 * Math.PI / 32.0));
            signal[i * 2] = (byte) sample;
            signal[i * 2 + 1] = (byte) (sample >> 8);
        }
        return signal;
    }

    private static void checkWindows(SpeechCues speech, ArrayBlockingQueue<Cue> received) throws Exception {
        byte[] zombie = synthesize("hey zombie");
        byte[] mobs = synthesize("mobs");
        byte[] enderman = synthesize("ender man");
        byte[] greeting = synthesize("hey");
        byte[] unrelated = synthesize("the garden is beautiful");
        Process helper = SpeechCues.launch();
        killAfter(helper, 55);
        try (var input = helper.getInputStream(); var output = new DataOutputStream(helper.getOutputStream())) {
            require(input.read() == 82, "Requires an installed English desktop speech recognizer");
            byte[][] cases = {zombie, mobs, enderman, greeting, unrelated, new byte[16000]};
            int[] expected = {1, 2, 3, 0, 0, 0};
            for (int i = 0; i < cases.length; i++) {
                output.writeInt(Integer.reverseBytes(cases[i].length));
                output.write(cases[i]);
                output.flush();
                require(input.read() == expected[i], "Unexpected decision in synthetic case " + i);
            }
        } finally {
            helper.destroyForcibly();
        }

        speech.setEnabled(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (speech.status() == SpeechCues.Status.STARTING && System.nanoTime() < deadline) Thread.sleep(25);
        require(speech.status() == SpeechCues.Status.READY, "Recognizer startup");
        for (int offset = 0; offset < zombie.length; offset += FRAME) {
            speech.acceptFrame(Arrays.copyOfRange(zombie, offset, offset + FRAME));
        }
        speech.endUtterance();
        require(received.poll(10, TimeUnit.SECONDS) == Cue.ZOMBIE, "End-to-end cue");
        speech.setEnabled(false);
        require(speech.status() == SpeechCues.Status.OFF && received.isEmpty(), "Opt-out or duplicate cue");
    }

    // Only fixed strings from the test cases above are supplied to this local synthesizer.
    private static byte[] synthesize(String phrase) throws Exception {
        String script = """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Speech
                $tts = [System.Speech.Synthesis.SpeechSynthesizer]::new()
                $voice = $tts.GetInstalledVoices() | Where-Object {
                    $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en'
                } | Select-Object -First 1
                if ($null -eq $voice) { exit 2 }
                $tts.SelectVoice($voice.VoiceInfo.Name)
                $format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(
                    16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
                    [System.Speech.AudioFormat.AudioChannel]::Mono)
                $stream = [System.IO.MemoryStream]::new()
                $tts.SetOutputToAudioStream($stream, $format)
                $tts.Speak('%s')
                $tts.SetOutputToNull()
                $stream.Position = 0
                $stream.CopyTo([Console]::OpenStandardOutput())
                $tts.Dispose()
                """.formatted(phrase);
        Path shell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0",
                "powershell.exe");
        Process synth = new ProcessBuilder(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", script).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        killAfter(synth, 15);
        try (var input = synth.getInputStream()) {
            byte[] pcm = input.readNBytes(96001);
            if (pcm.length > 96000 || pcm.length == 0 || (pcm.length & 1) != 0) {
                throw new IOException("Invalid synthetic PCM length");
            }
            require(synth.waitFor(5, TimeUnit.SECONDS) && synth.exitValue() == 0, "Synthetic voice unavailable");
            return pcm;
        } finally {
            synth.destroyForcibly();
        }
    }

    private static void killAfter(Process process, int seconds) {
        process.onExit().orTimeout(seconds, TimeUnit.SECONDS).exceptionally(failure -> {
            process.toHandle().destroyForcibly();
            return null;
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

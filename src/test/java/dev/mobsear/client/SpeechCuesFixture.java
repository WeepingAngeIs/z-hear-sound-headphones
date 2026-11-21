package dev.mobsear.client;

import dev.mobsear.net.Cue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Standalone Java 21 checks. All inputs are generated in memory; no audio device is opened. */
public final class SpeechCuesFixture {
    private static final int FRAME = SpeechCues.Utterances.FRAME_BYTES;

    public static void main(String[] args) throws Exception {
        checkSegmentation();
        var received = new ArrayBlockingQueue<Cue>(8);
        var aliasFile = Files.createTempFile("mobsear-alias-fixture-", ".properties");
        try (var speech = new SpeechCues(received::add, aliasFile)) {
            checkLexicon(aliasFile);
            Files.writeString(aliasFile, "skeleton=bone walker\n");
            require(speech.status() == SpeechCues.Status.OFF, "Recognition default");
            speech.acceptFrame(toneFrame());
            speech.endUtterance();
            require(received.isEmpty(), "Off state emitted a cue");
            if (args.length == 1 && args[0].equals("--windows-speech")) {
                checkWindows(speech, received, aliasFile);
            } else if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
                speech.setEnabled(true);
                require(speech.status() == SpeechCues.Status.UNAVAILABLE, "Unsupported host status");
            }
        } finally { Files.deleteIfExists(aliasFile); }
        System.out.println("Speech fixture passed.");
    }

    private static void checkLexicon(Path file) throws IOException {
        Files.writeString(file, """
                zombie=walker,creeper,hey,hey there
                skeleton=walker,bone walker,a|b,javascript:foo,extra
                spider=BATMAN,has four full words,longlonglonglonglong,one two three
                unknown_type=bogus
                """);
        var actual = new LinkedHashMap<String, Integer>();
        for (String pair : MobLexicon.grammar(file).split("\\|")) {
            String[] parts = pair.split(":");
            require(parts.length == 2, "Grammar entry delimiter");
            require(actual.put(parts[1], Integer.parseInt(parts[0])) == null, "Duplicate grammar phrase");
        }
        for (Cue cue : Cue.values()) {
            require(actual.get(cue.entityPath.replace('_', ' ')) == cue.ordinal() + 1, "Built-in cue collision");
        }
        require(actual.get("walker") == Cue.ZOMBIE.ordinal() + 1 && actual.get("bone walker") == Cue.SKELETON.ordinal() + 1,
                "Duplicate custom name or two-word alias precedence");
        require(actual.get("batman") == Cue.SPIDER.ordinal() + 1 && actual.get("one two three") == Cue.SPIDER.ordinal() + 1,
                "Case normalization or three-word alias");
        for (String blocked : new String[]{"hey", "hey there", "a", "b", "javascript", "foo", "extra", "bogus",
                "has four full words", "longlonglonglonglong"}) require(!actual.containsKey(blocked), "Reserved or malformed alias");
        Files.writeString(file, "skeleton=" + "a".repeat(16_385));
        require(MobLexicon.grammar(file).equals(MobLexicon.grammar(null)), "Oversize alias file");
        Files.writeString(file, "skeleton=bad" + (char) 92 + "uXYZ1");
        require(MobLexicon.grammar(file).equals(MobLexicon.grammar(null)), "Malformed properties fallback");
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
        require(clips.size() == 1 && clips.getFirst().length == 39 * FRAME, "Silence endpoint");
        require(Arrays.equals(clips.getFirst(), 10 * FRAME, 11 * FRAME, signal, 0, FRAME), "PCM changed");
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

    private static void checkWindows(SpeechCues speech, ArrayBlockingQueue<Cue> received, Path aliasFile) throws Exception {
        byte[] zombie = synthesize("zombie");
        byte[] greetingZombie = synthesize("hey zombie");
        byte[] zombies = synthesize("zombies");
        byte[] mobs = synthesize("mobs");
        byte[] enderman = synthesize("ender man");
        byte[] greeting = synthesize("hey");
        byte[] unrelated = synthesize("the garden is beautiful");
        byte[] conversation = synthesize("let me see");
        byte[] greetingOnly = synthesize("hey there");
        byte[] skeleton = synthesize("skeleton");
        byte[] creeper = synthesize("creeper");
        byte[] custom = synthesize("bone walker");
        byte[][] cases = {zombie, mobs, enderman, greeting, unrelated, new byte[16000], greetingZombie,
                zombies, conversation, greetingOnly, zombie, skeleton, creeper, custom};
        int[] expected = {1, 2, 3, 0, 0, 0, 1, 1, 0, 0, 1, 4, 5, 4};
        int[] decisions = decisions(SpeechCues.launch(aliasFile), cases);
        if (!Arrays.equals(decisions, expected)) {
            diagnoseSyntheticDecisions(cases, aliasFile);
            throw new AssertionError("Synthetic SAPI decisions " + Arrays.toString(decisions)
                    + " expected " + Arrays.toString(expected));
        }

        speech.setEnabled(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (speech.status() == SpeechCues.Status.STARTING && System.nanoTime() < deadline) Thread.sleep(25);
        require(speech.status() == SpeechCues.Status.READY, "Recognizer startup");
        var acousticClips = new ArrayList<byte[]>();
        var segmentation = new SpeechCues.Utterances(acousticClips::add);
        for (int offset = 0; offset < zombie.length; offset += FRAME) {
            byte[] frame = Arrays.copyOfRange(zombie, offset, offset + FRAME);
            segmentation.accept(frame);
            speech.acceptFrame(frame);
        }
        segmentation.finish();
        speech.endUtterance();
        Cue actual = received.poll(10, TimeUnit.SECONDS);
        if (actual != Cue.ZOMBIE) {
            System.err.println("End-to-end status=" + speech.status() + " clip bytes="
                    + acousticClips.stream().map(a -> a.length).toList() + " source bytes=" + zombie.length);
            diagnoseSyntheticDecisions(acousticClips.toArray(byte[][]::new), aliasFile);
            throw new AssertionError("End-to-end cue was " + actual);
        }
        speech.setEnabled(false);
        require(speech.status() == SpeechCues.Status.OFF && received.isEmpty(), "Opt-out or duplicate cue");
    }

    private static int[] decisions(Process helper, byte[][] cases) throws Exception {
        int[] actual = new int[cases.length];
        Arrays.fill(actual, -1);
        killAfter(helper, 55);
        try (var input = helper.getInputStream(); var output = new DataOutputStream(helper.getOutputStream())) {
            require(input.read() == 82, "Requires an installed English desktop speech recognizer");
            for (int i = 0; i < cases.length; i++) {
                output.writeInt(Integer.reverseBytes(cases[i].length));
                output.write(cases[i]);
                output.flush();
                actual[i] = input.read();
                if (actual[i] < 0) break;
            }
            return actual;
        } finally {
            helper.destroyForcibly();
        }
    }

    // This instrumentation exists only in the fixture source set. It prints fixed grammar labels,
    // scores and exception locations for synthetic cases, without displaying words or PCM.
    private static void diagnoseSyntheticDecisions(byte[][] cases, Path aliasFile) throws Exception {
        try (var source = SpeechCues.class.getResourceAsStream("recognize.ps1")) {
            require(source != null, "Missing speech helper resource");
            String helper = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            String recognize = "$result = $engine.Recognize()";
            helper = helper.replace(recognize, recognize + """

                        if ($null -eq $result) { [Console]::Error.WriteLine('SAPI no match') }
                        else {
                            $known = $result.Text.ToLowerInvariant() -replace '^hey ', ''
                            $id = if ($aliases.ContainsKey($known)) { $aliases[$known] } else { 0 }
                            [Console]::Error.WriteLine(('SAPI callGrammar={0} confidence={1:F3} aliasId={2}' -f
                                ($result.Grammar.Name -eq 'mob call'), $result.Confidence, $id))
                        }
                    """);
            helper = helper.replace("    exit 1", """
                        [Console]::Error.WriteLine(('SAPI helper error line={0} type={1} hresult={2}' -f
                            $_.InvocationInfo.ScriptLineNumber, $_.Exception.GetType().Name, $_.Exception.HResult))
                        exit 1""");
            var diagnostic = powershell(helper, ProcessBuilder.Redirect.INHERIT);
            diagnostic.environment().put("MOBSEAR_GRAMMAR", MobLexicon.grammar(aliasFile));
            decisions(diagnostic.start(), cases);
        }
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
        Process synth = powershell(script, ProcessBuilder.Redirect.DISCARD).start();
        killAfter(synth, 15);
        try (var input = synth.getInputStream()) {
            byte[] pcm = input.readNBytes(96001);
            if (pcm.length > 96000 || pcm.length == 0 || (pcm.length & 1) != 0) {
                throw new IOException("Invalid synthetic PCM length");
            }
            require(synth.waitFor(5, TimeUnit.SECONDS) && synth.exitValue() == 0, "Synthetic voice unavailable");
            // The recognizer normally receives a short pre-roll and silence endpoint from Utterances.
            byte[] utterance = new byte[3 * FRAME + pcm.length + 18 * FRAME];
            require(utterance.length <= 96000, "Synthetic phrase exceeds production duration");
            System.arraycopy(pcm, 0, utterance, 3 * FRAME, pcm.length);
            return utterance;
        } finally {
            synth.destroyForcibly();
        }
    }

    private static ProcessBuilder powershell(String script, ProcessBuilder.Redirect errors) {
        Path shell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0",
                "powershell.exe");
        return new ProcessBuilder(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", script).redirectError(errors);
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

package dev.mobsear.client;

import dev.mobsear.net.Cue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Standalone Java 21 checks. All inputs are generated in memory; no audio device is opened. */
public final class SpeechCuesFixture {
    private static final int FRAME = SpeechCues.Utterances.FRAME_BYTES;

    public static void main(String[] args) throws Exception {
        checkSegmentation();
        checkSpeciesCatalog();
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
                checkWindowsSpecies(aliasFile);
                writeSyntheticPitchPreview();
            } else if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
                speech.setEnabled(true);
                require(speech.status() == SpeechCues.Status.UNAVAILABLE, "Unsupported host status");
            }
        } finally { Files.deleteIfExists(aliasFile); }
        System.out.println("Speech fixture passed.");
    }

    /** Only the hosted synthetic spelling fixture can write these three demonstrations. */
    private static void writeSyntheticPitchPreview() throws Exception {
        String destination = System.getenv("MOBSEAR_SYNTH_PROOF_DIR");
        if (destination == null || destination.isBlank()) return;
        Path folder = Files.createDirectories(Path.of(destination));
        byte[] source = synthesize("Mobs hear my voice");
        var format = new AudioFormat(VoiceAudio.SAMPLE_RATE, 16, 1, true, false);
        for (String preset : new String[]{"natural", "squeaky", "deep"}) {
            int cents = switch (preset) {
                case "squeaky" -> VoicePreferences.SQUEAKY_CENTS;
                case "deep" -> VoicePreferences.DEEP_CENTS;
                default -> 0;
            };
            var outgoing = new OutgoingPitch();
            var rendered = new ByteArrayOutputStream(source.length + FRAME);
            for (int offset = 0; offset < source.length; offset += FRAME) {
                byte[] dry = Arrays.copyOfRange(source, offset, offset + FRAME);
                outgoing.route(dry, cents, wire -> { rendered.writeBytes(wire); return true; }, raw -> {});
            }
            byte[] pcm = Arrays.copyOf(rendered.toByteArray(), source.length);
            if (cents == 0) require(Arrays.equals(source, pcm), "Natural synthetic demonstration changed the input");
            else require(!Arrays.equals(source, pcm), "Synthetic demonstration was not shifted");
            Path path = folder.resolve("synthetic-spoken-" + preset + ".wav");
            try (var audio = new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / 2L)) {
                AudioSystem.write(audio, AudioFileFormat.Type.WAVE, path.toFile());
            }
        }
        System.out.println("SYNTHETIC_PITCH_SPEECH_PASS three software-generated clips, each " + source.length / 2 + " samples");
    }

    private static void checkLexicon(Path file) throws IOException {
        Files.writeString(file, """
                zombie=walker,creeper,hey,hey there
                skeleton=walker,bone walker,a|b,javascript:foo,extra
                spider=BATMAN,has four full words,longlonglonglonglong,one two three
                unknown_type=bogus
                """);
        var actual = new LinkedHashMap<String, Integer>();
        MobLexicon.phrases(file).forEach((name, cue) -> actual.put(name, cue.ordinal() + 1));
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
        require(MobLexicon.phrases(file).equals(MobLexicon.phrases(null)), "Oversize alias file");
        Files.writeString(file, "skeleton=bad" + (char) 92 + "uXYZ1");
        require(MobLexicon.phrases(file).equals(MobLexicon.phrases(null)), "Malformed properties fallback");
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

    private static void checkSpeciesCatalog() throws IOException {
        var names = new LinkedHashMap<String, List<String>>();
        names.put("cobblemon:nidoranf", List.of("Nidoran♀", "Nidoran"));
        names.put("cobblemon:nidoranm", List.of("Nidoran♂", "Nidoran"));
        names.put("pack:flabebe", List.of("Flabébé", "Flabebe"));
        names.put("pack:other", List.of("Zombie", "All Pokémon"));
        names.put("pack:unsafe", List.of("x:3|boom", "hey there"));
        names.put("pack:short", List.of("x", "ab"));
        names.put("not a registry id", List.of("extra"));
        names.put("pack:generic", List.of("Pokémon", "Everyone"));
        names.put("pack:untranslated", List.of("cobblemon.species.hello.name", "ピカチュウ"));
        var resolved = SpeechGrammar.speciesPhrases(names);
        require("cobblemon:nidoranf".equals(resolved.get("nidoran female"))
                && "cobblemon:nidoranm".equals(resolved.get("nidoran male")), "Gendered display names");
        var grammar = new SpeechGrammar(null, resolved);
        require(grammar.code("zombie") == Cue.ZOMBIE.ordinal() + 1, "Species displaced vanilla cue");
        require(grammar.code("pack zombie") > Cue.values().length, "Registry-qualified colliding name unavailable");
        require(grammar.code("flabebe") > Cue.values().length, "Accent-insensitive English name");
        for (String word : List.of("nidoran", "pokemon", "all pokemon", "everyone", "hey", "hey there",
                "x:3|boom", "a", "ab", "extra", "my nickname", "cobblemon species hello name"))
            require(grammar.code(word) == 0, "Ambiguous, unknown or unsupported label admitted");
        require(!grammar.dispatch(65535, c -> {}, id -> {}), "Invalid helper index");
        var mixed = SpeechGrammar.registries(Map.of("cobblemon:orb", List.of("Orb")),
                Map.of("cobblemon:pokemon", List.of("All creatures", "Pokemon"), "pack:orb", List.of("Orb"),
                       "other:shade", List.of("Shade"), "pack:shade", List.of("Shade")));
        var typed = new SpeechGrammar(null, mixed);
        require(typed.code("all creatures") == 0 && typed.code("orb") == 0 && typed.code("shade") == 0,
                "Generic Pokemon entity or ambiguous cross-kind name admitted");
        var speciesReply = new ArrayList<String>();
        var entityReply = new ArrayList<String>();
        for (String phrase : List.of("pokemon orb", "creature orb", "other shade", "pack shade")) {
            require(typed.dispatch(typed.code(phrase), c -> {}, speciesReply::add, entityReply::add), "Typed registry decision");
        }
        require(speciesReply.equals(List.of("cobblemon:orb"))
                && entityReply.equals(List.of("pack:orb", "other:shade", "pack:shade")), "Wrong kind or qualified ID");
        var boundary = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < 4097; i++) boundary.put("test:" + i, List.of("species " + i));
        require(SpeechGrammar.speciesPhrases(boundary).isEmpty(), "Oversize catalog partially admitted");
    }

    private static void checkWindowsSpecies(Path aliasFile) throws Exception {
        String filename = System.getenv("MOBSEAR_SPEECH_CATALOG");
        if (filename == null || filename.isBlank()) return;
        var names = new LinkedHashMap<String, List<String>>();
        for (String entry : Files.readAllLines(Path.of(filename))) {
            String[] fields = entry.split("\\t");
            require(fields.length == 3, "Pinned species fixture format");
            require(names.put(fields[0], List.of(fields[1], fields[2])) == null, "Duplicate registry ID");
        }
        require(names.size() == 1119, "Expected the complete registry count reported by the TEST installation");
        long begin = System.nanoTime();
        var entities = new LinkedHashMap<String, List<String>>();
        entities.put("minecraft:cow", List.of("Cow"));
        entities.put("minecraft:iron_golem", List.of("Iron Golem"));
        entities.put("minecraft:guardian", List.of("Guardian"));
        entities.put("cobblemon:pokemon", List.of("Pokémon", "Pokemon"));
        entities.put("uwudimension:blossom_warden", List.of("Owl Guardian", "Blossom Warden", "UwU Guardian"));
        entities.put("uwudimension:velvet_vesper", List.of("Velvet Vesper"));
        entities.put("mobsear:dungeon_mimic", List.of("Dungeon Mimic"));
        var registries = SpeechGrammar.registries(names, entities);
        var vocabulary = registries.species();
        var grammar = new SpeechGrammar(aliasFile, registries);
        verifyAllRegistryLabels(names, vocabulary, grammar);
        require("cobblemon:spectreonee".equals(vocabulary.get("spectreon")), "Add-on display name and registry ID differ");
        require("cobblemon:lunavee".equals(vocabulary.get("lunavee")), "TEST-matched public Lunavee registry/display name");
        require(grammar.code("guardian") == Cue.GUARDIAN.ordinal() + 1 && grammar.code("minecraft guardian") > Cue.values().length,
                "Classic guardian and exact registered guardian must remain distinct");
        var guarded = grammar.needsConfirmation();
        long guardedSpecies = guarded.stream().map(vocabulary::get).distinct().count();
        require(guarded.containsAll(List.of("spectreon", "spectrier")), "Pinned spelling-neighbor group");
        require(!guarded.contains("eevee") && !guarded.contains("nidoran female") && !guarded.contains("nidoran male"),
                "Unrelated or gender-explicit species suppressed");
        require(grammar.code("spectreon ending o n") == grammar.code("spectreon")
                && grammar.code("spectrier ending e r") == grammar.code("spectrier"), "Explicit registry spelling routes");
        vocabulary.forEach((phrase, id) -> {
            int code = grammar.code(phrase);
            require(code > Cue.values().length && grammar.dispatch(code,
                    unexpected -> { throw new AssertionError("Registry name emitted a vanilla cue"); },
                    actual -> require(id.equals(actual), "Registry name emitted the wrong species ID")),
                    "Missing registry decision");
        });
        require(grammar.code("pikachu") > 255 && grammar.code("nidoran female") != grammar.code("nidoran male"),
                "Large vocabulary wire index or gender separation");
        long buildMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        var encoded = new ByteArrayOutputStream();
        grammar.writeTo(new DataOutputStream(encoded));
        byte[] pikachu = synthesize("Pikachu");
        byte[] ditto = synthesize("hey Ditto");
        byte[][] clips = {pikachu, ditto, synthesize("Eevee"), synthesize("Dungeon"), synthesize("Spectreon"), synthesize("Spectrier"),
                synthesize("Spectreon ending O N"), synthesize("Spectrier ending E R"), synthesize("spider"),
                synthesize("hey"), synthesize("pokemon"), synthesize("all pokemon"),
                synthesize("the garden is beautiful"), synthesize("my little buddy"), new byte[16000],
                synthesize("Lunavee"), synthesize("hey Lunavee"), synthesize("Boxtail"), synthesize("Mikueon"),
                synthesize("cow"), synthesize("iron golem"), synthesize("Owl Guardian"), synthesize("oo woo guardian"),
                synthesize("you woo guardian"), synthesize("blossom warden"), synthesize("Velvet Vesper"), synthesize("guardian"),
                synthesize("call cow"), synthesize("call Owl Guardian"), synthesize("call oo woo guardian"),
                synthesize("call Mikueon"), synthesize("call"), synthesize("call pokemon"), synthesize("call the garden is beautiful"),
                synthesize("call Miku Eon"), synthesize("Dungeon Mimic"), synthesize("call Dungeon Mimic")};
        int spectreon = grammar.code("spectreon");
        int spectrier = grammar.code("spectrier");
        // For a bare close pair, exact or abstained is valid; qualified calls must return the one correct ID.
        int[][] expected = {{grammar.code("pikachu")}, {grammar.code("ditto")}, {grammar.code("eevee")}, {grammar.code("dungeon")},
                {spectreon, 0}, {spectrier, 0}, {spectreon}, {spectrier}, {Cue.SPIDER.ordinal() + 1}, {0}, {0}, {0}, {0}, {0}, {0},
                {grammar.code("lunavee")}, {grammar.code("lunavee")}, {grammar.code("boxtail")}, {grammar.code("mikueon"), 0},
                {grammar.code("cow"), 0}, {grammar.code("iron golem")}, {grammar.code("owl guardian"), 0}, {grammar.code("owl guardian"), 0},
                {grammar.code("owl guardian")}, {grammar.code("owl guardian")}, {grammar.code("velvet vesper")}, {Cue.GUARDIAN.ordinal() + 1},
                {grammar.code("cow")}, {grammar.code("owl guardian")}, {grammar.code("owl guardian")}, {grammar.code("mikueon"), 0}, {0}, {0}, {0},
                {grammar.code("mikueon")}, {grammar.code("dungeon mimic"), 0}, {grammar.code("dungeon mimic")}};
        require("cobblemon:boxtail".equals(vocabulary.get("boxtail")) && "cobblemon:mikueon".equals(vocabulary.get("mikueon")),
                "Global species pack display names");
        long started = System.nanoTime();
        int[] result = decisions(SpeechCues.launch(), grammar, clips);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        for (int i = 0; i < result.length; i++) {
            int decision = result[i];
            if (Arrays.stream(expected[i]).noneMatch(allowed -> allowed == decision)) {
                for (int j=0; j<result.length; j++) {
                    int index = j;
                    grammar.dispatch(result[j], c -> System.out.println("SYNTH_DECISION " + index + " mob=" + c),
                            s -> System.out.println("SYNTH_DECISION " + index + " species=" + s),
                            e -> System.out.println("SYNTH_DECISION " + index + " entity=" + e));
                }
                diagnoseSyntheticDecisions(clips, grammar);
                throw new AssertionError("Full English catalog decisions " + Arrays.toString(result)
                        + " allowed " + Arrays.deepToString(expected));
            }
        }
        System.out.println("MOBSEAR_SPECIES_NEIGHBORS_PASS needsConfirmation=" + guardedSpecies
                + " bareSpectreon=" + result[4] + " bareSpectrier=" + result[5]
                + " qualifiedSpectreon=" + result[6] + " qualifiedSpectrier=" + result[7]);
        System.out.println("MOBSEAR_LUNAVEE_SPEECH_PASS bare=" + result[15] + " greeting=" + result[16] + " registry=cobblemon:lunavee");
        System.out.println("MOBSEAR_GLOBAL_PACK_SPEECH_PASS boxtail=" + result[17] + " bareMikueon=" + result[18]
                + " explicitMikueon=" + result[30] + " spacedMikueon=" + result[34]);
        var exactEntities = new ArrayList<String>();
        for (int index : new int[]{27, 20, 28, 29, 23, 24, 25}) {
            require(grammar.dispatch(result[index], c -> { throw new AssertionError("Unexpected classic cue"); },
                    id -> { throw new AssertionError("Unexpected Pokemon species"); }, exactEntities::add), "Invalid entity code");
        }
        require(exactEntities.equals(List.of("minecraft:cow", "minecraft:iron_golem", "uwudimension:blossom_warden",
                "uwudimension:blossom_warden", "uwudimension:blossom_warden", "uwudimension:blossom_warden",
                "uwudimension:velvet_vesper")), "Specific synthetic entity-call outcomes " + exactEntities);
        require(grammar.dispatch(result[36], c -> {}, s -> {}, exactEntities::add) && exactEntities.getLast().equals("mobsear:dungeon_mimic"),
                "Dungeon Mimic must emit only the encounter registry ID");
        System.out.println("MOBSEAR_REGISTERED_ENTITY_SPEECH_PASS " + exactEntities + " vanillaGuardian=" + result[26]
                + " bareCow=" + result[19] + " bareOwlGuardian=" + result[21] + " bareOoWooGuardian=" + result[22]);
        var ids = new ArrayBlockingQueue<String>(4);
        var mobs = new ArrayBlockingQueue<Cue>(4);
        var entityIds = new ArrayBlockingQueue<String>(4);
        try (var speech = new SpeechCues(mobs::add, ids::add, entityIds::add, aliasFile, names, entities)) {
            speech.refreshCatalog(names, entities);
            require(speech.status() == SpeechCues.Status.OFF, "Catalog refresh enabled recognition");
            started = System.nanoTime();
            speech.setEnabled(true);
            awaitReady(speech);
            long readyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            speech.refreshSpecies(names);
            require(speech.status() == SpeechCues.Status.READY, "Unchanged registry restarted worker");
            for (int pos = 0; pos < pikachu.length; pos += FRAME)
                speech.acceptFrame(Arrays.copyOfRange(pikachu, pos, pos + FRAME));
            speech.endUtterance();
            require("cobblemon:pikachu".equals(ids.poll(8, TimeUnit.SECONDS)), "End-to-end species callback");
            require(mobs.isEmpty(), "Species emitted an extra vanilla cue");
            byte[] guardian = result[22] == grammar.code("owl guardian") ? clips[22] : clips[29];
            for (int pos = 0; pos < guardian.length; pos += FRAME)
                speech.acceptFrame(Arrays.copyOfRange(guardian, pos, pos + FRAME));
            speech.endUtterance();
            require("uwudimension:blossom_warden".equals(entityIds.poll(8, TimeUnit.SECONDS)) && ids.isEmpty() && mobs.isEmpty(),
                    "End-to-end UwU Guardian must emit only its registered entity ID");
            speech.refreshCatalog(Map.of(), Map.of());
            awaitReady(speech);
            require(ids.isEmpty(), "Removed registry produced a stale callback");
            String proof = System.getenv("MOBSEAR_SPECIES_PROOF");
            if (proof != null) Files.writeString(Path.of(proof), "cobblemon:pikachu\n", StandardCharsets.US_ASCII);
            System.out.println("MOBSEAR_SPECIES_SPEECH_PASS source=Cobblemon-1.8.4 English species="
                    + names.size() + " normalizedNames=" + vocabulary.size() + " grammarBytes=" + encoded.size()
                    + " javaBuildMs=" + buildMillis + " SAPIReadyMs=" + readyMillis + " startupAnd" + clips.length + "ClipsMs=" + elapsedMillis);
        }
    }

    private static void verifyAllRegistryLabels(Map<String, List<String>> names,
            Map<String, String> vocabulary, SpeechGrammar grammar) {
        var covered = new java.util.HashSet<String>();
        var excluded = new ArrayList<String>();
        int distinctLabels = 0;
        int matchedLabels = 0;
        for (var entry : names.entrySet()) {
            String id = entry.getKey();
            for (String label : new java.util.LinkedHashSet<>(entry.getValue())) {
                distinctLabels++;
                // Resolve one label alone to distinguish malformed labels from cross-species collisions.
                var single = SpeechGrammar.speciesPhrases(Map.of(id, List.of(label)));
                if (single.isEmpty()) {
                    excluded.add(id + " [" + label + "] unsupported");
                    continue;
                }
                String phrase = single.keySet().iterator().next();
                if (!id.equals(vocabulary.get(phrase))) {
                    excluded.add(id + " [" + label + "] ambiguous");
                    continue;
                }
                if (grammar.code(phrase) <= Cue.values().length) {
                    excluded.add(id + " [" + label + "] reserved vanilla call");
                    continue;
                }
                covered.add(id);
                matchedLabels++;
            }
        }
        for (String line : excluded) System.out.println("MOBSEAR_REGISTRY_LABEL_EXCEPTION " + line);
        var missing = new java.util.TreeSet<>(names.keySet());
        missing.removeAll(covered);
        System.out.println("MOBSEAR_REGISTRY_LABEL_COVERAGE ids=" + names.size() + " coveredIds=" + covered.size()
                + " distinctCanonicalAndDisplayLabels=" + distinctLabels + " matchedLabels=" + matchedLabels
                + " excludedLabels=" + excluded.size() + " missingIds=" + missing);
        require(missing.isEmpty(), "Species with no matching canonical or English display name: " + missing);
    }

    private static void awaitReady(SpeechCues speech) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (speech.status() == SpeechCues.Status.STARTING && System.nanoTime() < until) Thread.sleep(25);
        require(speech.status() == SpeechCues.Status.READY, "Desktop catalog startup " + speech.status());
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
        byte[] singular = synthesize("mob");
        byte[] namedEnderman = synthesize("Enderman");
        byte[] spider = synthesize("spider");
        byte[][] cases = {zombie, mobs, enderman, greeting, unrelated, new byte[16000], greetingZombie,
                zombies, conversation, greetingOnly, zombie, skeleton, creeper, custom, singular, namedEnderman, spider};
        int[] expected = {1, 2, 3, 0, 0, 0, 1, 1, 0, 0, 1, 4, 5, 4, 2, 3, 6};
        int[] decisions = decisions(SpeechCues.launch(), new SpeechGrammar(aliasFile, Map.of()), cases);
        if (!Arrays.equals(decisions, expected)) {
            diagnoseSyntheticDecisions(cases, aliasFile);
            throw new AssertionError("Synthetic SAPI decisions " + Arrays.toString(decisions)
                    + " expected " + Arrays.toString(expected));
        }

        speech.setEnabled(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (speech.status() == SpeechCues.Status.STARTING && System.nanoTime() < deadline) Thread.sleep(25);
        require(speech.status() == SpeechCues.Status.READY, "Recognizer startup");
        for (int cents : new int[]{VoicePreferences.SQUEAKY_CENTS, VoicePreferences.DEEP_CENTS}) {
            var acousticClips = new ArrayList<byte[]>();
            var segmentation = new SpeechCues.Utterances(acousticClips::add);
            var pitch = new OutgoingPitch();
            var changed = new java.util.concurrent.atomic.AtomicBoolean();
            for (int offset = 0; offset < zombie.length; offset += FRAME) {
                byte[] frame = Arrays.copyOfRange(zombie, offset, offset + FRAME);
                pitch.route(frame, cents, wire -> {
                    if (!Arrays.equals(wire, frame)) changed.set(true);
                    return true;
                }, original -> {
                    require(original == frame, "The recognizer received a processed pitch frame");
                    segmentation.accept(original);
                    speech.acceptFrame(original);
                });
            }
            require(changed.get(), "Outgoing synthetic speech was never shifted");
            segmentation.finish();
            speech.endUtterance();
            Cue actual = received.poll(10, TimeUnit.SECONDS);
            if (actual != Cue.ZOMBIE) {
                System.err.println("End-to-end status=" + speech.status() + " clip bytes="
                        + acousticClips.stream().map(a -> a.length).toList() + " source bytes=" + zombie.length);
                diagnoseSyntheticDecisions(acousticClips.toArray(byte[][]::new), aliasFile);
                throw new AssertionError("End-to-end cue was " + actual + " with outgoing cents " + cents);
            }
        }
        var phrases = new byte[][] {namedEnderman, spider, singular, mobs};
        var calls = new Cue[] {Cue.ENDERMAN, Cue.SPIDER, Cue.MOBS, Cue.MOBS};
        var results = new ArrayList<String>();
        for (boolean automatic : new boolean[]{false, true}) {
            for (int i = 0; i < phrases.length; i++) {
                var filter = new CaptureGate();
                var clips = new ArrayList<byte[]>();
                var diagnostics = new SpeechCues.Utterances(clips::add);
                Runnable endpoint = () -> { speech.endUtterance(); diagnostics.finish(); };
                var outgoing = new OutgoingPitch();
                int cents = automatic ? VoicePreferences.SQUEAKY_CENTS : 0;
                // Gate, noise suppression, pitch and segmentation match the opted-in game path.
                for (int pos = 0; pos < phrases[i].length; pos += FRAME) {
                    byte[] frame = Arrays.copyOfRange(phrases[i], pos, pos + FRAME);
                    filter.accept(frame, automatic, 50, true, true, (wire, original) ->
                        outgoing.route(wire, cents, accepted -> true, ignored -> {
                            diagnostics.accept(original);
                            speech.acceptFrame(original);
                        }), endpoint);
                }
                // Open mic must finish by itself. PTT also accepts a key release.
                if (!automatic) endpoint.run();
                Cue recognized = received.poll(8, TimeUnit.SECONDS);
                if (recognized != calls[i]) diagnoseSyntheticDecisions(clips.toArray(byte[][]::new), aliasFile);
                require(recognized == calls[i], "Synthetic call " + i + " auto=" + automatic
                        + " expected " + calls[i] + " got " + recognized);
                if (!automatic) results.add(recognized.name());
            }
            System.out.println("MOBSEAR_CALL_INPUT_PASS automatic=" + automatic + " noiseSuppression=true");
        }
        String proof = System.getenv("MOBSEAR_CUE_PROOF");
        if (proof != null) Files.write(Path.of(proof), results, StandardCharsets.US_ASCII);
        System.out.println("MOBSEAR_SYNTH_CALL_PASS Enderman, spider, mob, mobs -> " + results);
        speech.setEnabled(false);
        require(speech.status() == SpeechCues.Status.OFF && received.isEmpty(), "Opt-out or duplicate cue");
    }

    private static int[] decisions(Process helper, SpeechGrammar grammar, byte[][] cases) throws Exception {
        int[] actual = new int[cases.length];
        Arrays.fill(actual, -1);
        killAfter(helper, 55);
        try (var input = new DataInputStream(helper.getInputStream()); var output = new DataOutputStream(helper.getOutputStream())) {
            grammar.writeTo(output);
            require(input.read() == 82, "Requires an installed English desktop speech recognizer");
            for (int i = 0; i < cases.length; i++) {
                output.writeInt(Integer.reverseBytes(cases[i].length));
                output.write(cases[i]);
                output.flush();
                actual[i] = input.readUnsignedShort();
            }
            return actual;
        } finally {
            helper.destroyForcibly();
        }
    }

    // This instrumentation exists only in the fixture source set. It prints fixed grammar labels,
    // scores and exception locations for synthetic cases, without displaying words or PCM.
    private static void diagnoseSyntheticDecisions(byte[][] cases, Path aliasFile) throws Exception {
        diagnoseSyntheticDecisions(cases, new SpeechGrammar(aliasFile, Map.of()));
    }

    private static void diagnoseSyntheticDecisions(byte[][] cases, SpeechGrammar grammar) throws Exception {
        try (var source = SpeechCues.class.getResourceAsStream("recognize.ps1")) {
            require(source != null, "Missing speech helper resource");
            String helper = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            helper = helper.replace("$engine.LoadGrammar($calls)", "$engine.MaxAlternates = 8\n    $engine.LoadGrammar($calls)");
            String recognize = "$result = $engine.Recognize()";
            helper = helper.replace(recognize, recognize + """

                        if ($null -eq $result) { [Console]::Error.WriteLine('SAPI no match') }
                        else {
                            $known = $result.Text.ToLowerInvariant() -replace '^(hey|call) ', ''
                            $id = if ($aliases.ContainsKey($known)) { $aliases[$known] } else { 0 }
                            [Console]::Error.WriteLine(('SAPI callGrammar={0} confidence={1:F3} aliasId={2}' -f
                                ($result.Grammar.Name -eq 'mob call'), $result.Confidence, $id))
                            $alternates = foreach ($alt in $result.Alternates) {
                                $name = $alt.Text.ToLowerInvariant() -replace '^(hey|call) ', ''
                                $altId = if ($aliases.ContainsKey($name)) { $aliases[$name] } else { 0 }
                                ('{0}/{1:F3}' -f $altId, $alt.Confidence)
                            }
                            [Console]::Error.WriteLine(('SAPI alternative IDs/scores ' + ($alternates -join ',')))
                        }
                    """);
            String cleanup = "$engine.SetInputToNull()\n            $stream.Dispose()\n            [Array]::Clear";
            helper = helper.replace("\r\n", "\n").replace(cleanup, """
                    $engine.SetInputToNull()
                    $stream.Position = 0
                    $calls.Enabled = $false
                    try {
                        $engine.SetInputToAudioStream($stream, $format)
                        $native = $engine.Recognize()
                        $known = if ($null -eq $native) { '' } else { $native.Text.ToLowerInvariant() -replace '^(hey|call) ', '' }
                        $nativeId = if ($aliases.ContainsKey($known)) { $aliases[$known] } else { 0 }
                        [Console]::Error.WriteLine(('SAPI native dictation registryId={0} confidence={1:F3} guardianToken={2} nWords={3}' -f
                            $nativeId, $native.Confidence, ($known -match '\\bguardian\\b'), $known.Split(' ').Count))
                    } finally {
                        $engine.SetInputToNull()
                        $calls.Enabled = $true
                    }
                    $stream.Position = 0
                    $background.Enabled = $false
                    try {
                        $engine.SetInputToAudioStream($stream, $format)
                        $forced = $engine.Recognize()
                        $known = if ($null -eq $forced) { '' } else { $forced.Text.ToLowerInvariant() -replace '^(hey|call) ', '' }
                        $forcedId = if ($aliases.ContainsKey($known)) { $aliases[$known] } else { 0 }
                        [Console]::Error.WriteLine(('SAPI command-only registryId={0} confidence={1:F3}' -f $forcedId, $forced.Confidence))
                    } finally {
                        $engine.SetInputToNull()
                        $background.Enabled = $true
                    }
                    """ + cleanup);
            helper = helper.replace("    exit 1", """
                        [Console]::Error.WriteLine(('SAPI helper error line={0} type={1} hresult={2}' -f
                            $_.InvocationInfo.ScriptLineNumber, $_.Exception.GetType().Name, $_.Exception.HResult))
                        exit 1""");
            var diagnostic = powershell(helper, ProcessBuilder.Redirect.INHERIT);
            decisions(diagnostic.start(), grammar, cases);
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

package dev.mobsear.client;

import dev.mobsear.net.Cue;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Each local helper session can return only an index into its own immutable decision table. */
final class SpeechGrammar {
    private static final int MAX_SPECIES = 4096;
    private static final int MAX_NAMES_PER_SPECIES = 2;
    private static final int MAX_NAMES_PER_ENTITY = 6;
    private static final int MAX_ID_LENGTH = 128;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PHRASE = Pattern.compile("[a-z]{1,24}(?: [a-z]{1,24}){0,3}");
    private static final Pattern ENTITY_PHRASE = Pattern.compile("[a-z]{1,24}(?: [a-z]{1,24}){0,7}");
    private static final Pattern UWU = Pattern.compile("\\buwu\\b");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile(" +");
    private static final String[] DIGITS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
    private static final Set<String> RESERVED = Set.of("hey", "pokemon", "all pokemon", "every pokemon",
            "pokemons", "everyone", "everybody", "everything", "all", "all species", "species", "mon", "mons",
            "mob", "mobs", "wild pokemon", "all mobs");

    private final Map<String, Integer> phrases;
    private final List<Registered> registeredByIndex;
    private final Map<String, List<String>> speciesLabels = new TreeMap<>();
    static final int NEARBY_GUESS = 0x8000;
    private final Set<String> needsConfirmation;
    private final Set<String> explicitOnly;
    private static final Cue[] MOBS = Cue.values();
    record Registries(Map<String, String> species, Map<String, String> entities, Set<String> neutral) {}
    private record Registered(boolean entity, String id) {}

    static boolean reserved(String phrase) {
        return RESERVED.contains(phrase) || phrase.startsWith("hey ") || phrase.equals("call") || phrase.startsWith("call ");
    }

    /** Input labels come exclusively from a species registry. Duplicate names are unusable for both species. */
    static Map<String, String> speciesPhrases(Map<String, List<String>> catalog) {
        return registries(catalog, Map.of()).species();
    }

    static Registries registries(Map<String, List<String>> species, Map<String, List<String>> entities) {
        var unique = new TreeMap<String, Registered>();
        var ambiguous = new TreeMap<String, Set<Registered>>();
        addLabels(species, false, unique, ambiguous);
        addLabels(entities, true, unique, ambiguous);
        var qualified = new TreeMap<String, Registered>();
        var duplicateQualifiers = new TreeMap<String, Set<Registered>>();
        var classicNames = MobLexicon.phrases(null).keySet();
        unique.forEach((phrase, owner) -> {
            if (classicNames.contains(phrase)) qualify(qualified, duplicateQualifiers, phrase, owner);
        });
        ambiguous.forEach((phrase, owners) -> {
            unique.remove(phrase);
            for (Registered owner : owners) {
                qualify(qualified, duplicateQualifiers, phrase, owner);
                if (owners.stream().anyMatch(other -> other.entity != owner.entity)) {
                    add(qualified, duplicateQualifiers, (owner.entity ? "creature " : "pokemon ") + phrase, owner);
                }
            }
        });
        qualified.forEach((phrase, owner) -> add(unique, duplicateQualifiers, phrase, owner));
        var neutral = new HashSet<>(ambiguous.keySet());
        neutral.addAll(duplicateQualifiers.keySet());
        neutral.forEach(unique::remove);
        var speciesNames = new TreeMap<String, String>();
        var entityNames = new TreeMap<String, String>();
        unique.forEach((phrase, owner) -> (owner.entity ? entityNames : speciesNames).put(phrase, owner.id));
        return new Registries(Map.copyOf(speciesNames), Map.copyOf(entityNames), Set.copyOf(neutral));
    }

    private static void qualify(Map<String, Registered> qualified, Map<String, Set<Registered>> duplicateQualifiers,
                                String phrase, Registered owner) {
        String namespace = owner.id.substring(0, owner.id.indexOf(':')).replace('_', ' ').replace('.', ' ');
        String specific = spokenName(namespace + " " + phrase, true);
        if (!specific.isEmpty()) add(qualified, duplicateQualifiers, specific, owner);
    }

    private static void addLabels(Map<String, List<String>> catalog, boolean entity,
                                  Map<String, Registered> unique, Map<String, Set<Registered>> ambiguous) {
        if (catalog.size() > MAX_SPECIES) return;
        catalog.forEach((id, names) -> {
            if (id == null || id.length() > MAX_ID_LENGTH || !ID.matcher(id).matches() || names == null) return;
            if (entity && id.equals("cobblemon:pokemon")) return;
            var owner = new Registered(entity, id);
            for (int i = 0; i < Math.min(names.size(), entity ? MAX_NAMES_PER_ENTITY : MAX_NAMES_PER_SPECIES); i++) {
                String phrase = spokenName(names.get(i), entity);
                if (phrase.isEmpty()) continue;
                add(unique, ambiguous, phrase, owner);
                if (entity && UWU.matcher(phrase).find()) {
                    add(unique, ambiguous, UWU.matcher(phrase).replaceAll("oo woo"), owner);
                    add(unique, ambiguous, UWU.matcher(phrase).replaceAll("ooh woo"), owner);
                    add(unique, ambiguous, UWU.matcher(phrase).replaceAll("you woo"), owner);
                }
            }
        });
    }

    private static void add(Map<String, Registered> unique, Map<String, Set<Registered>> ambiguous, String phrase, Registered owner) {
        Registered other = unique.putIfAbsent(phrase, owner);
        if (other != null && !other.equals(owner)) {
            var owners = ambiguous.computeIfAbsent(phrase, ignored -> new HashSet<>());
            owners.add(other);
            owners.add(owner);
        }
    }

    static String spokenName(String label, boolean entity) {
        if (label == null || label.length() > (entity ? 80 : 64)) return "";
        String plain = MARKS.matcher(Normalizer.normalize(label, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT).replace("♀", " female ").replace("♂", " male ")
                .replace("'", "").replace("’", "");
        if (plain.endsWith("-f")) plain = plain.substring(0, plain.length() - 2) + " female";
        if (plain.endsWith("-m")) plain = plain.substring(0, plain.length() - 2) + " male";
        if (plain.startsWith("mr. ")) plain = "mister " + plain.substring(4);
        if (plain.startsWith("mr ")) plain = "mister " + plain.substring(3);
        if (plain.endsWith(" jr.")) plain = plain.substring(0, plain.length() - 4) + " junior";
        if (plain.endsWith(" jr")) plain = plain.substring(0, plain.length() - 3) + " junior";
        plain = plain.replace('-', ' ').replace(':', ' ');
        var words = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (c >= '0' && c <= '9') words.append(' ').append(DIGITS[c - '0']).append(' ');
            else words.append(c);
        }
        String phrase = SPACES.matcher(words).replaceAll(" ").strip();
        if (phrase.length() < 3 || phrase.length() > (entity ? 80 : 54) || !(entity ? ENTITY_PHRASE : PHRASE).matcher(phrase).matches()
                || reserved(phrase)) return "";
        return phrase;
    }

    SpeechGrammar(Path aliases, Map<String, String> speciesPhrases) {
        this(aliases, new Registries(speciesPhrases, Map.of(), Set.of()));
    }

    SpeechGrammar(Path aliases, Registries registries) {
        var entries = new LinkedHashMap<String, Integer>();
        MobLexicon.phrases(aliases).forEach((name, cue) -> entries.put(name, cue.ordinal() + 1));
        registries.neutral.forEach(phrase -> entries.putIfAbsent(phrase, 0));
        var ids = new ArrayList<Registered>();
        var codes = new java.util.HashMap<Registered, Integer>();
        var groups = new TreeMap<String, List<String>>();
        var vocabulary = new TreeMap<String, Registered>();
        registries.species.forEach((phrase, id) -> vocabulary.put(phrase, new Registered(false, id)));
        registries.entities.forEach((phrase, id) -> vocabulary.put(phrase, new Registered(true, id)));
        vocabulary.forEach((phrase, target) -> {
            if (entries.containsKey(phrase)) return; // Vanilla calls and explicit local mob aliases keep precedence.
            int code = codes.computeIfAbsent(target, key -> {
                ids.add(key);
                return MOBS.length + ids.size();
            });
            entries.put(phrase, code);
            if (!target.entity) speciesLabels.computeIfAbsent(target.id, key -> new ArrayList<>()).add(phrase);
            String shape = spellingShape(phrase);
            if (!shape.isEmpty()) groups.computeIfAbsent(shape, ignored -> new ArrayList<>()).add(phrase);
        });
        var confirm = new HashSet<String>();
        for (var group : groups.values()) {
            if (group.stream().map(entries::get).distinct().limit(2).count() < 2) continue;
            for (String name : group) {
                confirm.add(name);
                String lastTwo = name.substring(name.length() - 2);
                boolean needsThree = group.stream().anyMatch(other -> !entries.get(other).equals(entries.get(name))
                        && other.endsWith(lastTwo));
                String ending = name.substring(name.length() - (needsThree ? 3 : 2));
                StringBuilder spelling = new StringBuilder(name).append(" ending");
                for (char letter : ending.toCharArray()) spelling.append(' ').append(letter);
                // Conflicting explicit labels are neutral, even if a datapack deliberately reuses a qualifier.
                entries.merge(spelling.toString(), entries.get(name), (a, b) -> a.equals(b) ? a : 0);
            }
        }
        needsConfirmation = Set.copyOf(confirm);
        // A clear word break can help the English recognizer pronounce a made-up compound.
        // Retain every letter of the registered name and enable this spelling only after 'call'.
        var separated = new TreeMap<String, Integer>();
        vocabulary.forEach((name, owner) -> {
            if (name.length() < 7 || name.length() > 24 || name.indexOf(' ') >= 0 || needsConfirmation.contains(name)) return;
            int code = entries.getOrDefault(name, 0);
            if (code <= MOBS.length) return;
            int added = 0;
            for (int i = 3; i <= name.length() - 3 && added < 2; i++) {
                char left = name.charAt(i - 1), right = name.charAt(i);
                if (left == right || "aeiou".indexOf(left) < 0 || "aeiou".indexOf(right) < 0) continue;
                String split = name.substring(0, i) + " " + name.substring(i);
                if (entries.containsKey(split)) continue;
                separated.merge(split, code, (a, b) -> a.equals(b) ? a : 0);
                added++;
            }
        });
        explicitOnly = Set.copyOf(separated.keySet());
        entries.putAll(separated);
        phrases = Collections.unmodifiableMap(entries);
        registeredByIndex = List.copyOf(ids);
    }

    /** Only equal-length, single-word labels sharing at least six leading letters can form a group. */
    private static String spellingShape(String phrase) {
        if (phrase.length() < 7 || phrase.indexOf(' ') >= 0) return "";
        int prefix = Math.max(6, phrase.length() - 3);
        var shape = new StringBuilder(phrase.substring(0, prefix)).append(':');
        for (int i = prefix; i < phrase.length(); i++) {
            shape.append("aeiouy".indexOf(phrase.charAt(i)) >= 0 ? 'v' : 'c');
        }
        return shape.toString();
    }

    Set<String> needsConfirmation() {
        return needsConfirmation;
    }

    int code(String phrase) {
        return phrases.getOrDefault(phrase, 0);
    }

    List<String> nearbyLabels(List<String> nearestFirst) {
        var labels = new ArrayList<String>();
        for (var id : nearestFirst) labels.addAll(speciesLabels.getOrDefault(id, List.of()));
        return labels;
    }

    String speciesId(int code) {
        int index = code - MOBS.length - 1;
        if (index < 0 || index >= registeredByIndex.size() || registeredByIndex.get(index).entity) return null;
        return registeredByIndex.get(index).id;
    }

    boolean dispatch(int code, Consumer<Cue> mob, Consumer<String> species) {
        return dispatch(code, mob, species, id -> {});
    }

    boolean dispatch(int code, Consumer<Cue> mob, Consumer<String> species, Consumer<String> entity) {
        if ((code & NEARBY_GUESS) != 0) {
            code &= ~NEARBY_GUESS;
            if (speciesId(code) == null) return false;
        }
        if (code < 0 || code > MOBS.length + registeredByIndex.size()) return false;
        if (code > MOBS.length) {
            var registered = registeredByIndex.get(code - MOBS.length - 1);
            (registered.entity ? entity : species).accept(registered.id);
        } else if (code > 0) mob.accept(MOBS[code - 1]);
        return true;
    }

    /** The vocabulary uses a length-prefixed binary pipe, independent of the OS environment size limit. */
    void writeTo(DataOutputStream output) throws IOException {
        var encoded = new StringBuilder();
        phrases.forEach((phrase, code) -> {
            int variant = explicitOnly.contains(phrase) ? 2 : needsConfirmation.contains(phrase) ? 1 : 0;
            encoded.append(code).append(':').append(variant).append(':').append(phrase).append('\n');
        });
        byte[] bytes = encoded.toString().getBytes(StandardCharsets.US_ASCII);
        output.writeInt(Integer.reverseBytes(bytes.length));
        output.flush();
        // A modpack registry can exceed the capacity of the Windows anonymous pipe.
        for (int offset = 0; offset < bytes.length; offset += 2048) {
            output.write(bytes, offset, Math.min(2048, bytes.length - offset));
            output.flush();
        }
    }
}

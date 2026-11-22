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
    private static final int MAX_ID_LENGTH = 128;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PHRASE = Pattern.compile("[a-z]{1,24}(?: [a-z]{1,24}){0,3}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile(" +");
    private static final String[] DIGITS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};
    private static final Set<String> RESERVED = Set.of("hey", "pokemon", "all pokemon", "every pokemon",
            "pokemons", "everyone", "everybody", "everything", "all", "all species", "species", "mon", "mons",
            "mob", "mobs", "wild pokemon", "all mobs");

    private final Map<String, Integer> phrases;
    private final List<String> speciesByIndex;
    private final Set<String> needsConfirmation;
    private static final Cue[] MOBS = Cue.values();

    static boolean reserved(String phrase) {
        return RESERVED.contains(phrase) || phrase.startsWith("hey ");
    }

    /** Input labels come exclusively from a species registry. Duplicate names are unusable for both species. */
    static Map<String, String> speciesPhrases(Map<String, List<String>> catalog) {
        if (catalog.size() > MAX_SPECIES) return Map.of();
        var unique = new TreeMap<String, String>();
        var ambiguous = new HashSet<String>();
        catalog.forEach((id, names) -> {
            if (id == null || id.length() > MAX_ID_LENGTH || !ID.matcher(id).matches() || names == null) return;
            for (int i = 0; i < Math.min(names.size(), MAX_NAMES_PER_SPECIES); i++) {
                String phrase = spokenName(names.get(i));
                if (phrase.isEmpty()) continue;
                String other = unique.putIfAbsent(phrase, id);
                if (other != null && !other.equals(id)) ambiguous.add(phrase);
            }
        });
        ambiguous.forEach(unique::remove);
        return Map.copyOf(unique);
    }

    private static String spokenName(String label) {
        if (label == null || label.length() > 64) return "";
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
        if (phrase.length() < 3 || phrase.length() > 54 || !PHRASE.matcher(phrase).matches()
                || reserved(phrase)) return "";
        return phrase;
    }

    SpeechGrammar(Path aliases, Map<String, String> speciesPhrases) {
        var entries = new LinkedHashMap<String, Integer>();
        MobLexicon.phrases(aliases).forEach((name, cue) -> entries.put(name, cue.ordinal() + 1));
        var ids = new ArrayList<String>();
        var codes = new TreeMap<String, Integer>();
        var groups = new TreeMap<String, List<String>>();
        new TreeMap<>(speciesPhrases).forEach((phrase, id) -> {
            if (entries.containsKey(phrase)) return; // Vanilla calls and explicit local mob aliases keep precedence.
            int code = codes.computeIfAbsent(id, key -> {
                ids.add(key);
                return MOBS.length + ids.size();
            });
            entries.put(phrase, code);
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
        phrases = Collections.unmodifiableMap(entries);
        speciesByIndex = List.copyOf(ids);
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

    boolean dispatch(int code, Consumer<Cue> mob, Consumer<String> species) {
        if (code < 0 || code > MOBS.length + speciesByIndex.size()) return false;
        if (code > MOBS.length) species.accept(speciesByIndex.get(code - MOBS.length - 1));
        else if (code > 0) mob.accept(MOBS[code - 1]);
        return true;
    }

    /** The vocabulary uses a length-prefixed binary pipe, independent of the OS environment size limit. */
    void writeTo(DataOutputStream output) throws IOException {
        var encoded = new StringBuilder();
        phrases.forEach((phrase, code) -> encoded.append(code).append(needsConfirmation.contains(phrase) ? ":1:" : ":0:")
                .append(phrase).append('\n'));
        byte[] bytes = encoded.toString().getBytes(StandardCharsets.US_ASCII);
        output.writeInt(Integer.reverseBytes(bytes.length));
        output.write(bytes);
        output.flush();
    }
}

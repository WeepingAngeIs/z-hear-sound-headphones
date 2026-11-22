package dev.mobsear.client;

import dev.mobsear.net.Cue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** Only curated and explicitly configured labels enter the local recognizer grammar. */
final class MobLexicon {
    private static final Pattern SHORT_CALL = Pattern.compile("[a-z]{1,16}(?: [a-z]{1,16}){0,2}");
    private static final String TEMPLATE = """
            # Mobs Hear U: extra English phrases for the on-device recognizer.
            # Use up to four comma-separated aliases per mob. Toggle mob calls off/on after editing.
            # Examples (uncomment to enable):
            # skeleton=bone walker,bones
            # zombie=walker
            # creeper=green menace
            """;

    static Map<String, Cue> phrases(Path file) {
        var phrases = new LinkedHashMap<String, Cue>();
        for (Cue cue : Cue.values()) {
            String name = cue.entityPath.replace('_', ' ');
            phrases.put(name, cue);
        }
        add(phrases, Cue.ZOMBIE, "zombies", "zombified piglin", "zombified piglins", "husk", "drowned", "zombie villager");
        add(phrases, Cue.MOBS, "mob");
        add(phrases, Cue.ENDERMAN, "endermen", "ender man", "ender men");
        add(phrases, Cue.SKELETON, "skeletons", "stray", "bogged", "wither skeleton");
        add(phrases, Cue.SPIDER, "spiders", "cave spider");
        add(phrases, Cue.SLIME, "slimes", "magma cube");
        add(phrases, Cue.GUARDIAN, "elder guardian");
        if (file != null) {
            Properties custom = read(file);
            for (Cue cue : Cue.values()) {
                var aliases = custom.getProperty(cue.entityPath, "").split(",");
                for (int i = 0; i < Math.min(4, aliases.length); i++) {
                    String a = aliases[i].strip().toLowerCase(Locale.ROOT);
                    if (a.length() <= 32 && SHORT_CALL.matcher(a).matches() && !SpeechGrammar.reserved(a)) {
                        phrases.putIfAbsent(a, cue);
                    }
                }
            }
        }
        return phrases;
    }

    private static void add(Map<String, Cue> phrases, Cue cue, String... aliases) {
        for (String alias : aliases) phrases.putIfAbsent(alias, cue);
    }

    private static Properties read(Path file) {
        var values = new Properties();
        try {
            if (Files.notExists(file)) {
                Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            if (Files.size(file) <= 16_384) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            values.clear();
        }
        return values;
    }
}

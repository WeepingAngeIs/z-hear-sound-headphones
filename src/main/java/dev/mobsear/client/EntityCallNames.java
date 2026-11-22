package dev.mobsear.client;

import dev.mobsear.net.MobCallTypes;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/** Use registered mob types and English labels. Never read an individual entity's custom name. */
final class EntityCallNames {
    private Language language;
    private Map<String, List<String>> entries = Map.of();

    Map<String, List<String>> snapshot() {
        Language selected = Language.getInstance();
        if (language == selected) return entries;
        var mc = Minecraft.getInstance();
        Language english = mc.getLanguageManager().getSelected().startsWith("en_") ? selected
                : ClientLanguage.loadFrom(mc.getResourceManager(), List.of("en_us"), false);
        var names = new HashMap<String, List<String>>();
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!MobCallTypes.eligibleType(id, type)) continue;
            var labels = new LinkedHashSet<String>();
            if (english.has(type.getDescriptionId())) labels.add(english.getOrDefault(type.getDescriptionId()));
            labels.add(id.getPath().replace('_', ' ').replace('/', ' '));
            if (MobCallTypes.OWL_GUARDIAN.equals(id)) {
                labels.add("Owl Guardian");
                labels.add("UwU Guardian");
            }
            names.put(id.toString(), List.copyOf(labels));
        }
        if (ModList.get().isLoaded("cumimic")) {
            names.put(MobCallTypes.DUNGEON_MIMIC.toString(), List.of("Dungeon Mimic"));
        }
        entries = Map.copyOf(names);
        language = selected;
        return entries;
    }

    static Component display(ResourceLocation id) {
        if (MobCallTypes.DUNGEON_MIMIC.equals(id) && ModList.get().isLoaded("cumimic"))
            return Component.translatable("mobsear.speech.dungeon_mimic");
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(type -> type.getDescription()).orElse(null);
    }
}

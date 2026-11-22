package dev.mobsear.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.entity.Mob;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import dev.mobsear.net.Packets;

/** Loaded only on an opted-in client that negotiated Cobblemon species calls. */
final class CobblemonNames {
    private static final AtomicLong registryRevision = new AtomicLong();
    static {
        PokemonSpecies.INSTANCE.getObservable().subscribe((Consumer<PokemonSpecies>) ignored -> registryRevision.incrementAndGet());
    }
    private long revision = -1;
    private Language language;
    private Map<String, List<String>> entries = Map.of();

    Map<String, List<String>> snapshot() {
        long current = registryRevision.get();
        Language selected = Language.getInstance();
        if (revision == current && selected == language) return entries;
        boolean english = Minecraft.getInstance().getLanguageManager().getSelected().startsWith("en_");
        var names = new HashMap<String,List<String>>();
        for (var species : PokemonSpecies.getSpecies()) {
            String id = species.getResourceIdentifier().toString();
            if (id.length() > Packets.SpeciesCall.MAX_ID_LENGTH) continue;
            String canonical = species.getName();
            String localized = english && selected.has(species.getTranslationKey()) ? species.getTranslatedName().getString() : canonical;
            names.put(id, canonical.equals(localized) ? List.of(canonical) : List.of(canonical, localized));
        }
        entries = Map.copyOf(names);
        revision = current;
        language = selected;
        return entries;
    }

    static Component display(ResourceLocation id) {
        var species = PokemonSpecies.getByIdentifier(id);
        if (species == null) return null;
        return Language.getInstance().has(species.getTranslationKey()) ? species.getTranslatedName() : Component.literal(species.getName());
    }

    static String nearbyName(Mob mob) {
        return mob instanceof PokemonEntity entity ? entity.getPokemon().getSpecies().getName() : null;
    }
}

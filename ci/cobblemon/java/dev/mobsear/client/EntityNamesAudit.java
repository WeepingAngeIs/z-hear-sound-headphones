package dev.mobsear.client;

import dev.mobsear.net.MobCallTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Exercise the genuine installed client catalog, including public addon labels, without constructing every mob. */
public final class EntityNamesAudit {
    public static void check(String side) {
        if (!"1".equals(System.getenv("MOBSEAR_POKEMON_PACK"))) return;
        var entries=new TreeMap<>(new EntityCallNames().snapshot());
        for (var type:BuiltInRegistries.ENTITY_TYPE) {
            var id=BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (MobCallTypes.eligibleType(id,type)!=entries.containsKey(id.toString()))
                throw new IllegalStateException("Entity registry/catalog mismatch at "+id);
        }
        for (String required:new String[]{"minecraft:cow","minecraft:cave_spider","minecraft:wither","minecraft:ender_dragon"})
            if (!entries.containsKey(required)) throw new IllegalStateException("Missing living type "+required);
        if ("1".equals(System.getenv("MOBSEAR_ENTITY_PACK")) &&
                (!entries.containsKey("twilightforest:deer") || !entries.containsKey("twilightforest:naga")))
            throw new IllegalStateException("Missing the loaded Twilight Forest mob names");
        for (String excluded:new String[]{"minecraft:item","minecraft:arrow","minecraft:armor_stand","minecraft:player","cobblemon:pokemon"})
            if (entries.containsKey(excluded)) throw new IllegalStateException("Unexpected generic call "+excluded);
        var owl=MobCallTypes.OWL_GUARDIAN;
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(owl) && !entries.get(owl.toString()).containsAll(java.util.List.of("Owl Guardian","UwU Guardian")))
            throw new IllegalStateException("Missing the registered Owl Guardian aliases");
        if (net.neoforged.fml.ModList.get().isLoaded("cumimic") &&
                !java.util.List.of("Dungeon Mimic").equals(entries.get(MobCallTypes.DUNGEON_MIMIC.toString())))
            throw new IllegalStateException("Missing distinct native Dungeon Mimic call");
        var rows=new ArrayList<String>();
        rows.add("id\tlabels");
        entries.forEach((id,labels)->rows.add(id+"\t"+String.join("|",labels)));
        try {
            var root=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"));
            var dir=root.resolve("catalogs");
            Files.createDirectories(dir);
            Files.write(dir.resolve("entity-"+side+".tsv"),rows);
            var species=new CobblemonNames().snapshot();
            var grammar = new SpeechGrammar(null, SpeechGrammar.registries(species, entries));
            int verified=0;
            for (var line:Files.readAllLines(root.resolve("test-species.tsv"))) {
                var fields=line.split("\t",-1);
                var labels=species.get(fields[0]);
                var display=CobblemonNames.display(ResourceLocation.parse(fields[0]));
                if (labels==null || !labels.contains(fields[1]) || !labels.contains(fields[2])
                        || display==null || !fields[2].equals(display.getString()))
                    throw new IllegalStateException("Wrong live English label for "+fields[0]+": expected="+fields[2]+" actual="+labels);
                if (grammar.nearbyLabels(java.util.List.of(fields[0])).stream()
                        .noneMatch(phrase -> fields[0].equals(grammar.speciesId(grammar.code(phrase)))))
                    throw new IllegalStateException("No callable base name in the merged speech vocabulary for " + fields[0]);
                verified++;
            }
            System.out.println("POKEMON_REGISTRY_PASS endpoint="+side+" englishLabels="+verified+" callableBaseSpecies="+verified);
        } catch (IOException ex) { throw new IllegalStateException("Cannot save real entity names",ex); }
        long modded=entries.keySet().stream().map(ResourceLocation::parse).filter(id->!id.getNamespace().equals("minecraft")).count();
        System.out.println("ENTITY_REGISTRY_PASS endpoint="+side+" eligible="+entries.size()+" modded="+modded);
    }
}

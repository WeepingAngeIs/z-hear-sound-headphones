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
        for (String excluded:new String[]{"minecraft:item","minecraft:arrow","minecraft:armor_stand","minecraft:player","cobblemon:pokemon"})
            if (entries.containsKey(excluded)) throw new IllegalStateException("Unexpected generic call "+excluded);
        var owl=MobCallTypes.OWL_GUARDIAN;
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(owl) && !entries.get(owl.toString()).containsAll(java.util.List.of("Owl Guardian","UwU Guardian")))
            throw new IllegalStateException("Missing the registered Owl Guardian aliases");
        var rows=new ArrayList<String>();
        rows.add("id\tlabels");
        entries.forEach((id,labels)->rows.add(id+"\t"+String.join("|",labels)));
        try {
            var dir=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"),"catalogs");
            Files.createDirectories(dir);
            Files.write(dir.resolve("entity-"+side+".tsv"),rows);
        } catch (IOException ex) { throw new IllegalStateException("Cannot save real entity names",ex); }
        long modded=entries.keySet().stream().map(ResourceLocation::parse).filter(id->!id.getNamespace().equals("minecraft")).count();
        System.out.println("ENTITY_REGISTRY_PASS endpoint="+side+" eligible="+entries.size()+" modded="+modded);
    }
}

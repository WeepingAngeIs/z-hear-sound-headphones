package dev.mobsear.server;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.TreeSet;

/** Verify the synchronized catalog on real game endpoints, without spawning an entity per ID. */
final class PokemonRegistryAudit {
    static void check(String side) {
        if (!"1".equals(System.getenv("MOBSEAR_POKEMON_PACK"))) return;
        var root=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"));
        try {
            var expected=new TreeSet<String>();
            var names=new TreeMap<String,String>();
            for (String line:Files.readAllLines(root.resolve("test-species.tsv"))) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("id\t")) continue;
                String[] fields=line.split("\t",-1);
                String id=fields[0];
                if (!expected.add(id)) throw PokemonProbe.fail("Duplicate TEST catalog ID "+id);
                names.put(id,fields[1]);
            }
            int profileCount=expected.size();
            if (profileCount==0 || !expected.add("hearfixture:chimekin")) throw PokemonProbe.fail("Invalid expected TEST catalog");
            var found=new TreeMap<String,Species>();
            for (var s:PokemonSpecies.getSpecies()) {
                var id=s.getResourceIdentifier();
                if (found.put(id.toString(),s)!=null || PokemonSpecies.getByIdentifier(id)!=s)
                    throw PokemonProbe.fail(side+" ambiguous base species "+id);
            }
            if (!found.keySet().equals(expected)) {
                var missing=new TreeSet<>(expected); missing.removeAll(found.keySet());
                var extra=new TreeSet<>(found.keySet()); extra.removeAll(expected);
                throw PokemonProbe.fail(side+" registry differs, missing="+missing.stream().limit(12).toList()+" extra="+extra.stream().limit(12).toList());
            }
            int alternates=0;
            var lines=new ArrayList<String>();
            lines.add("id\tname\talternate_forms");
            for (var entry:found.entrySet()) {
                var s=entry.getValue();
                if (names.containsKey(entry.getKey()) && !names.get(entry.getKey()).equals(s.getName()))
                    throw PokemonProbe.fail(side+" wrong canonical name for "+entry.getKey()+" actual="+s.getName());
                var forms=new TreeSet<String>();
                for (var f:s.getForms()) {
                    if (f.getSpecies()!=s) throw PokemonProbe.fail(side+" form "+f.getName()+" has wrong base species "+entry.getKey());
                    forms.add(f.getName());
                    alternates++;
                }
                lines.add(entry.getKey()+"\t"+s.getName()+"\t"+String.join("|",forms));
            }
            var folder=root.resolve("catalogs");
            Files.createDirectories(folder);
            Files.write(folder.resolve(side+".tsv"),lines);
            System.out.println("POKEMON_REGISTRY_PASS endpoint="+side+" testIds="+profileCount+" fixtureIds=1 resolved="+found.size()+" alternateForms="+alternates);
        } catch (IOException ex) { throw PokemonProbe.fail("Cannot read/write "+side+" registry audit: "+ex); }
    }
}

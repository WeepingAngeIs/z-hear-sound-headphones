package dev.mobsear.server;

import net.minecraft.resources.ResourceLocation;

enum PokemonCase {
    PIKACHU("pikachu", 12), EEVEE("eevee", 24), LOOK("pikachu", 14), APPROACH("eevee", 10),
    ADDON("acideon", 18), NAMESPACE("hearfixture:chimekin", 18), NEAR_EDGE("pikachu", 31),
    FAR("eevee", 36), WRONG("eevee", 15), OWNER("pikachu", 12), OFFLINE_OWNER("eevee", 12),
    BATTLE("pikachu", 12), CAPTURE("eevee", 12), PASSIVE("pikachu", 12),
    BLOCKED("eevee", 10), COOLDOWN_PRIME("pikachu", 14), COOLDOWN_HELD("pikachu", 14), COOLDOWN_EXPIRED("pikachu", 14),
    WALKING_PIKACHU("pikachu", 14), WALKING_EEVEE("eevee", 14), INTERRUPTED("eevee", 10),
    PACK_FORM("oricorio", 8), PACK_CUSTOM("lunavee", 8), GLOBAL_PACK("boxtail", 8),
    NAMED_COW("minecraft:cow", 12), OTHER_NAME("minecraft:cow", 12), NAMED_FAR("minecraft:cow", 36),
    NAMED_HOSTILE("minecraft:cave_spider", 22), BROAD_COW("minecraft:cow", 22),
    BOSS_CHARGING("minecraft:wither", 22), BOSS_FIGHTING("minecraft:wither", 22);

    final ResourceLocation species;
    final int distance;
    PokemonCase(String name, int distance) {
        this.species = ResourceLocation.parse(name.contains(":") ? name : "cobblemon:" + name);
        this.distance = distance;
    }
    boolean accepts() { return ordinal() <= NEAR_EDGE.ordinal() || this == BLOCKED || this == COOLDOWN_PRIME || this == COOLDOWN_EXPIRED || walking() || this == INTERRUPTED || this == PACK_FORM || this == PACK_CUSTOM || this == GLOBAL_PACK || this==NAMED_COW || this==NAMED_HOSTILE; }
    boolean ordinaryEntity() { return ordinal()>=NAMED_COW.ordinal(); }
    boolean walking() { return this==WALKING_PIKACHU || this==WALKING_EEVEE; }
    boolean controlled() { return this == LOOK || this == APPROACH || this == BLOCKED; }
    boolean reusesSubject() { return this == COOLDOWN_HELD || this == COOLDOWN_EXPIRED; }
    static PokemonCase[] requiredRegistryEntries() {
        return "1".equals(System.getenv("MOBSEAR_POKEMON_PACK"))
                ? new PokemonCase[]{PIKACHU,ADDON,NAMESPACE,PACK_FORM,PACK_CUSTOM,GLOBAL_PACK}
                : new PokemonCase[]{PIKACHU,ADDON,NAMESPACE};
    }
    void checkTrackedForm(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity, String observer) {
        if (this!=PACK_FORM) return;
        var data=entity.getPokemon();
        if (!species.equals(data.getSpecies().getResourceIdentifier()) || !"Pom-Pom".equals(data.getForm().getName())
                || !data.getAspects().contains("pom-pom-style")) {
            throw PokemonProbe.fail(observer+" saw wrong form of "+data.getSpecies().getResourceIdentifier()+
                    " form="+data.getForm().getName()+" aspects="+data.getAspects());
        }
    }
    ResourceLocation spoken() {
        if (this==OTHER_NAME) return ResourceLocation.withDefaultNamespace("pig");
        return this == WRONG ? PIKACHU.species : species;
    }
}

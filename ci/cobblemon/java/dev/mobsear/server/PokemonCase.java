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
    BOSS_CHARGING("minecraft:wither", 22), BOSS_FIGHTING("minecraft:wither", 22),
    FOREST_DEER("twilightforest:deer", 12), NAGA_COMBAT("twilightforest:naga", 22),
    OWL_GUARDIAN("uwudimension:blossom_warden", 12),
    VELVET_IDLE("uwudimension:velvet_vesper", 24), VELVET_GUARD("uwudimension:velvet_vesper", 18),
    DUNGEON_MIMIC("minecraft:zombie", 20), ORDINARY_ZOMBIE("minecraft:zombie", 20), NAMED_ONLY_ZOMBIE("minecraft:zombie", 20),
    OWL_OFFENDER("uwudimension:blossom_warden", 22);

    final ResourceLocation species;
    final int distance;
    PokemonCase(String name, int distance) {
        this.species = ResourceLocation.parse(name.contains(":") ? name : "cobblemon:" + name);
        this.distance = distance;
    }
    boolean accepts() {
        return switch (this) {
            case PIKACHU, EEVEE, LOOK, APPROACH, ADDON, NAMESPACE, NEAR_EDGE, BLOCKED, COOLDOWN_PRIME, COOLDOWN_EXPIRED,
                    WALKING_PIKACHU, WALKING_EEVEE, INTERRUPTED, PACK_FORM, PACK_CUSTOM, GLOBAL_PACK,
                    NAMED_COW, NAMED_HOSTILE, FOREST_DEER, OWL_GUARDIAN, VELVET_IDLE, DUNGEON_MIMIC -> true;
            case FAR, WRONG, OWNER, OFFLINE_OWNER, BATTLE, CAPTURE, PASSIVE, COOLDOWN_HELD, OTHER_NAME, NAMED_FAR,
                    BROAD_COW, BOSS_CHARGING, BOSS_FIGHTING, NAGA_COMBAT, OWL_OFFENDER, VELVET_GUARD,
                    ORDINARY_ZOMBIE, NAMED_ONLY_ZOMBIE -> false;
        };
    }
    boolean ordinaryEntity() { return ordinal()>=NAMED_COW.ordinal(); }
    boolean mimicCase() { return this==DUNGEON_MIMIC || this==ORDINARY_ZOMBIE || this==NAMED_ONLY_ZOMBIE; }
    boolean guardPicture() { return this==BOSS_CHARGING || this==NAGA_COMBAT || this==OWL_OFFENDER || this==VELVET_GUARD; }
    boolean beforePicture() { return this==PIKACHU || this==NAMED_COW || this==FOREST_DEER || this==OWL_GUARDIAN || this==VELVET_IDLE || this==DUNGEON_MIMIC; }
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
        if (mimicCase()) return ResourceLocation.fromNamespaceAndPath("mobsear","dungeon_mimic");
        if (this==OTHER_NAME) return ResourceLocation.withDefaultNamespace("pig");
        return this == WRONG ? PIKACHU.species : species;
    }
}

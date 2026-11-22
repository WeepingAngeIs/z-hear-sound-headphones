package dev.mobsear.server;

import net.minecraft.resources.ResourceLocation;

enum PokemonCase {
    PIKACHU("pikachu", 12), EEVEE("eevee", 24), LOOK("pikachu", 14), APPROACH("eevee", 10),
    ADDON("acideon", 18), NAMESPACE("hearfixture:chimekin", 18), NEAR_EDGE("pikachu", 31),
    FAR("eevee", 36), WRONG("eevee", 15), OWNER("pikachu", 12), OFFLINE_OWNER("eevee", 12),
    BATTLE("pikachu", 12), CAPTURE("eevee", 12), PASSIVE("pikachu", 12),
    BLOCKED("eevee", 10), COOLDOWN_PRIME("pikachu", 14), COOLDOWN_HELD("pikachu", 14), COOLDOWN_EXPIRED("pikachu", 14);

    final ResourceLocation species;
    final int distance;
    PokemonCase(String name, int distance) {
        this.species = ResourceLocation.parse(name.contains(":") ? name : "cobblemon:" + name);
        this.distance = distance;
    }
    boolean accepts() { return ordinal() <= NEAR_EDGE.ordinal() || this == BLOCKED || this == COOLDOWN_PRIME || this == COOLDOWN_EXPIRED; }
    boolean controlled() { return this == LOOK || this == APPROACH || this == BLOCKED; }
    boolean reusesSubject() { return this == COOLDOWN_HELD || this == COOLDOWN_EXPIRED; }
    ResourceLocation spoken() { return this == WRONG ? PIKACHU.species : species; }
}

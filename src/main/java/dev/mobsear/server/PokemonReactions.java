package dev.mobsear.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.ModList;

/** The standalone mod can load this class without resolving any optional Cobblemon types. */
public final class PokemonReactions {
    private PokemonReactions() {}

    public static void register() {
        if (ModList.get().isLoaded("cobblemon")) CobblemonCalls.register();
    }

    public static void hear(ServerPlayer speaker, ResourceLocation species, double radius) {
        tryCall(speaker, species, radius);
    }

    static boolean tryCall(ServerPlayer speaker, ResourceLocation species, double radius) {
        if (ModList.get().isLoaded("cobblemon") && species != null && Double.isFinite(radius) && radius > 0
                && speaker.isAlive() && !speaker.isRemoved() && !speaker.isSpectator() && !speaker.isCreative()) {
            return CobblemonCalls.hear(speaker, species, radius);
        }
        return false;
    }

    static boolean isReacting(Mob mob) {
        return ModList.get().isLoaded("cobblemon") && CobblemonCalls.isReacting(mob);
    }

    static boolean isPokemon(Mob mob) {
        return ModList.get().isLoaded("cobblemon") && CobblemonCalls.isPokemon(mob);
    }
}

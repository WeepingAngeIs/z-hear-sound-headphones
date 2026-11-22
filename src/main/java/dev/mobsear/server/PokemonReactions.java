package dev.mobsear.server;

import dev.mobsear.MobsEar;
import java.util.function.ToIntFunction;
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
        hear(speaker, species, radius, mob -> mob.getRandom().nextDouble() < MobsEar.wildPokemonApproachChance() ? 0 : 1);
    }

    static void hear(ServerPlayer speaker, ResourceLocation species, double radius, ToIntFunction<Mob> roll) {
        if (ModList.get().isLoaded("cobblemon") && species != null && Double.isFinite(radius) && radius > 0
                && speaker.isAlive() && !speaker.isRemoved() && !speaker.isSpectator() && !speaker.isCreative()) {
            CobblemonCalls.hear(speaker, species, radius, roll);
        }
    }

    static boolean isReacting(Mob mob) {
        return ModList.get().isLoaded("cobblemon") && CobblemonCalls.isReacting(mob);
    }
}

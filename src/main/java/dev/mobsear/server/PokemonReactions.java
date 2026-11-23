package dev.mobsear.server;

import dev.mobsear.net.Packets;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

/** The standalone mod can load this class without resolving any optional Cobblemon types. */
public final class PokemonReactions {
    private static final WeakHashMap<ServerPlayer, Integer> lastQuery = new WeakHashMap<>();
    private PokemonReactions() {}

    public static void register() {
        if (ModList.get().isLoaded("cobblemon")) CobblemonCalls.register();
    }

    public static void hear(ServerPlayer speaker, ResourceLocation species, double radius) {
        tryCall(speaker, species, radius);
    }

    public static void sendNearby(ServerPlayer player, int radius) {
        if (!player.connection.hasChannel(Packets.NearbySpecies.TYPE)) return;
        int tick = player.serverLevel().getServer().getTickCount();
        Integer previous = lastQuery.get(player);
        if (previous != null && tick - previous < 8) return;
        lastQuery.put(player, tick);
        var ids = player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.isCreative()
                && !NearbyMics.inputBlocked(player) ? CobblemonCalls.nearbySpecies(player, radius) : List.<ResourceLocation>of();
        PacketDistributor.sendToPlayer(player, new Packets.NearbySpecies(player.level().dimension().location(), ids));
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

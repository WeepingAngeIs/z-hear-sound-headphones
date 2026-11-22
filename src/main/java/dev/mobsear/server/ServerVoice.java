package dev.mobsear.server;

import dev.mobsear.MobsEar;
import dev.mobsear.net.Cue;
import dev.mobsear.net.Packets;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class ServerVoice {
    // All payload handlers and resets run on the Minecraft server thread.
    private static final Map<ServerPlayer, TalkWindow> sessions = new WeakHashMap<>();

    private ServerVoice() {}

    private static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator();
    }

    public static void relay(ServerPlayer speaker, Packets.Up frame, Iterable<ServerPlayer> candidates,
                             double radius, BiConsumer<ServerPlayer, Packets.Down> emit) {
        if (!eligible(speaker) || NearbyMics.inputBlocked(speaker) || frame.pcm().length != Packets.FRAME_BYTES || !(radius > 0)) return;
        long now = System.nanoTime();
        if (!sessions.computeIfAbsent(speaker, key -> new TalkWindow(now)).acceptFrame(now)) return;
        NearbyMics.heard(speaker, frame.pcm());
        var level = speaker.serverLevel();
        if (HordeMode.LIVE.optedIn(speaker)) {
            HordeMode.LIVE.heard(speaker, frame.pcm(), radius, MobsEar.hordeRules(), level.getServer().getTickCount());
        }
        for (ServerPlayer listener : candidates) {
            if (listener == speaker || listener.level() != level || !eligible(listener) || NearbyMics.deafened(listener)) continue;
            double distance = speaker.distanceToSqr(listener);
            if (distance >= radius * radius) continue;
            float gain = (float) (1.0 - Math.sqrt(distance) / radius);
            emit.accept(listener, new Packets.Down(speaker.getUUID(), level.dimension().location(), gain, frame.pcm()));
        }
    }

    private static boolean acceptCall(ServerPlayer speaker) {
        TalkWindow window = sessions.get(speaker);
        return eligible(speaker) && !NearbyMics.inputBlocked(speaker) && !speaker.isCreative()
                && window != null && window.acceptCue(System.nanoTime());
    }

    public static void cue(ServerPlayer speaker, Cue cue, double radius) {
        if (acceptCall(speaker)) MobReactions.handle(speaker, cue, radius);
    }

    public static void speciesCue(ServerPlayer speaker, ResourceLocation species, double radius) {
        if (acceptCall(speaker)) PokemonReactions.hear(speaker, species, radius);
    }

    public static void forget(ServerPlayer player) {
        sessions.remove(player);
        NearbyMics.forget(player);
        HordeMode.LIVE.forget(player);
    }
    public static void clear() { sessions.clear(); NearbyMics.clear(); }
}

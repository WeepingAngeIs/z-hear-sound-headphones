package dev.mobsear.server;

import dev.mobsear.MobsEar;
import dev.mobsear.net.Cue;
import dev.mobsear.net.CallFailure;
import dev.mobsear.net.Packets;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerVoice {
    // All payload handlers and resets run on the Minecraft server thread.
    private static final Map<ServerPlayer, TalkWindow> sessions = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> lastDecline = new WeakHashMap<>();

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

    private static boolean acceptCall(ServerPlayer speaker, long requestId) {
        TalkWindow window = sessions.get(speaker);
        CallFailure failure;
        if (!eligible(speaker) || speaker.isCreative()) failure = CallFailure.GAME_MODE;
        else if (NearbyMics.inputBlocked(speaker)) failure = CallFailure.INPUT_BLOCKED;
        else failure = window == null ? CallFailure.NO_RECENT_VOICE : window.tryCue(System.nanoTime());
        if (failure == null) return true;
        decline(speaker, requestId, failure);
        return false;
    }

    private static void decline(ServerPlayer speaker, long requestId, CallFailure cause) {
        if (requestId <= 0 || speaker.connection == null || !speaker.connection.hasChannel(Packets.CallDeclined.TYPE)) return;
        long now = System.nanoTime();
        Long previous = lastDecline.get(speaker);
        if (previous != null && now - previous < 500_000_000L) return;
        lastDecline.put(speaker, now);
        PacketDistributor.sendToPlayer(speaker, new Packets.CallDeclined(requestId, cause));
    }

    public static void cue(ServerPlayer speaker, Cue cue, double radius) {
        cue(speaker, cue, radius, 0);
    }

    public static void cue(ServerPlayer speaker, Cue cue, double radius, long requestId) {
        if (!acceptCall(speaker, requestId)) return;
        ResourceLocation listener = MobReactions.react(speaker, cue, radius);
        if (listener != null) confirm(speaker, requestId, listener, false);
        else decline(speaker, requestId, CallFailure.NO_ELIGIBLE_MOB);
    }

    public static void speciesCue(ServerPlayer speaker, ResourceLocation species, double radius) {
        speciesCue(speaker, species, radius, 0);
    }

    public static void speciesCue(ServerPlayer speaker, ResourceLocation species, double radius, long requestId) {
        if (!acceptCall(speaker, requestId)) return;
        if (PokemonReactions.tryCall(speaker, species, radius)) confirm(speaker, requestId, species, true);
        else decline(speaker, requestId, CallFailure.NO_ELIGIBLE_MOB);
    }

    public static void entityCue(ServerPlayer speaker, ResourceLocation entityType, double radius) {
        entityCue(speaker, entityType, radius, 0);
    }

    public static void entityCue(ServerPlayer speaker, ResourceLocation entityType, double radius, long requestId) {
        if (!acceptCall(speaker, requestId)) return;
        ResourceLocation listener = NamedMobReactions.hear(speaker, entityType, radius);
        if (listener != null) confirm(speaker, requestId, listener, false);
        else decline(speaker, requestId, CallFailure.NO_ELIGIBLE_MOB);
    }

    private static void confirm(ServerPlayer speaker, long requestId, ResourceLocation id, boolean species) {
        if (requestId > 0 && id.toString().length() <= Packets.MAX_CALL_ID_LENGTH && speaker.connection != null
                && speaker.connection.hasChannel(Packets.Heard.TYPE))
            PacketDistributor.sendToPlayer(speaker, new Packets.Heard(requestId, id, species));
    }

    public static void forget(ServerPlayer player) {
        sessions.remove(player);
        lastDecline.remove(player);
        NearbyMics.forget(player);
        HordeMode.LIVE.forget(player);
    }
    public static void clear() { sessions.clear(); lastDecline.clear(); NearbyMics.clear(); }
}

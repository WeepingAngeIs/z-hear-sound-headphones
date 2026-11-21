package dev.mobsear.server;

import dev.mobsear.net.MicPresence;
import dev.mobsear.net.MicState;
import dev.mobsear.net.VoiceSignal;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** All state and sends run on the server main thread. No positions or speaking flags come from clients. */
public final class NearbyMics {
    private static final WeakHashMap<ServerPlayer, Status> ACTIVE = new WeakHashMap<>();
    private NearbyMics() {}

    private static final class Status {
        MicPresence.Controls controls;
        int updated, heard, level;
        Status(MicPresence.Controls controls, int tick) { this.controls = controls; updated = tick; }
    }

    public static void controls(ServerPlayer player, MicPresence.Controls controls) {
        if (player.isRemoved() || !player.isAlive() || player.isSpectator()) return;
        int tick = player.serverLevel().getServer().getTickCount();
        Status status = ACTIVE.get(player);
        if (status == null) ACTIVE.put(player, new Status(controls, tick));
        else if (tick - status.updated >= 3) {
            status.updated = tick;
            status.controls = controls;
            if (controls.muted() || controls.deafened()) status.level = 0;
        }
    }

    static boolean inputBlocked(ServerPlayer player) {
        Status status = ACTIVE.get(player);
        return status != null && (status.controls.muted() || status.controls.deafened());
    }

    static boolean deafened(ServerPlayer player) {
        Status status = ACTIVE.get(player);
        return status != null && status.controls.deafened();
    }

    static void heard(ServerPlayer player, byte[] pcm) {
        Status status = ACTIVE.get(player);
        if (status == null || inputBlocked(player)) return;
        double level = VoiceSignal.meter(VoiceSignal.rms(pcm));
        if (level > 0) {
            status.heard = player.serverLevel().getServer().getTickCount();
            status.level = Math.max(1, (int) Math.round(level * 255));
        }
    }

    public static void tick(MinecraftServer server, int radius) {
        int tick = server.getTickCount();
        if ((tick & 3) != 0) return;
        ACTIVE.entrySet().removeIf(e -> tick - e.getValue().updated > 60 || !e.getKey().isAlive()
                || e.getKey().isRemoved() || e.getKey().isSpectator());
        for (ServerPlayer speaker : ACTIVE.keySet()) {
            var nearby = speaker.serverLevel().getEntitiesOfClass(ServerPlayer.class, speaker.getBoundingBox().inflate(radius));
            distribute(speaker, nearby, radius, (viewer, snapshot) -> {
                if (viewer.connection.hasChannel(MicPresence.Nearby.TYPE)) PacketDistributor.sendToPlayer(viewer, snapshot);
            });
        }
    }

    static void distribute(ServerPlayer speaker, Iterable<ServerPlayer> nearby, int radius,
                           BiConsumer<ServerPlayer, MicPresence.Nearby> emit) {
        Status status = ACTIVE.get(speaker);
        if (status == null) return;
        var level = speaker.serverLevel();
        var c = status.controls;
        boolean speaking = status.level != 0 && level.getServer().getTickCount() - status.heard < 8;
        MicState state = c.deafened() ? MicState.DEAFENED : c.muted() ? MicState.MUTED :
                speaking ? MicState.SPEAKING : MicState.IDLE;
        var snapshot = new MicPresence.Nearby(speaker.getUUID(), level.dimension().location(), radius,
                c.style(), state, state == MicState.SPEAKING ? status.level : 0);
        for (var viewer : nearby) {
            if (viewer == speaker || viewer.level() != level || viewer.isSpectator() || !viewer.isAlive()
                    || viewer.isRemoved() || speaker.isInvisibleTo(viewer)
                    || viewer.distanceToSqr(speaker) >= (double) radius * radius) continue;
            emit.accept(viewer, snapshot);
        }
    }

    public static void forget(ServerPlayer player) { ACTIVE.remove(player); }
    public static void clear() { ACTIVE.clear(); }
}

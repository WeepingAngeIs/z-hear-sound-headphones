package dev.mobsear;

import dev.mobsear.net.Packets;
import dev.mobsear.server.ServerVoice;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod("mobsear")
public final class MobsEar {
    private final ModConfigSpec.IntValue radius;
    // The physical client entry point supplies the receiver. Dedicated servers load no audio classes.
    public static Consumer<Packets.Down> playback = frame -> {};

    public MobsEar(IEventBus bus, ModContainer container) {
        var config = new ModConfigSpec.Builder();
        radius = config.comment("Hearing distance in blocks for players and mob calls.")
                .defineInRange("voiceRadius", 32, 1, 96);
        container.registerConfig(ModConfig.Type.SERVER, config.build());
        bus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> forget(event));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> forget(event));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.Clone event) -> {
            if (event.getOriginal() instanceof ServerPlayer player) ServerVoice.forget(player);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> ServerVoice.clear());
    }

    private static void forget(PlayerEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) ServerVoice.forget(player);
    }

    private void register(RegisterPayloadHandlersEvent event) {
        var reg = event.registrar("1").optional();
        reg.playToServer(Packets.Up.TYPE, Packets.Up.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerVoice.relay(player, packet, player.serverLevel().players(), radius.getAsInt(), (listener, down) -> {
                    if (listener.connection.hasChannel(Packets.Down.TYPE)) PacketDistributor.sendToPlayer(listener, down);
                });
            }
        });
        reg.playToServer(Packets.Call.TYPE, Packets.Call.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) ServerVoice.cue(player, packet.cue(), radius.getAsInt());
        });
        reg.playToClient(Packets.Down.TYPE, Packets.Down.CODEC, (packet, context) -> playback.accept(packet));
    }
}

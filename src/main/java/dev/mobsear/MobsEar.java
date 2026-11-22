package dev.mobsear;

import dev.mobsear.net.Packets;
import dev.mobsear.net.MicPresence;
import dev.mobsear.server.NearbyMics;
import dev.mobsear.server.HordeMode;
import dev.mobsear.server.NamedMobReactions;
import dev.mobsear.server.PokemonReactions;
import dev.mobsear.server.ServerVoice;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod("mobsear")
public final class MobsEar {
    private static final ModConfigSpec SERVER_CONFIG;
    private static final ModConfigSpec.IntValue radius, hordeCooldown, hordeCap;
    private static final ModConfigSpec.BooleanValue hordeEnabled;
    private static final ModConfigSpec.DoubleValue pokemonApproachChance;
    private static final WeakHashMap<ServerPlayer, Integer> lastHordeRequest = new WeakHashMap<>();
    static {
        var config = new ModConfigSpec.Builder();
        radius = config.comment("Hearing distance in blocks for players and mob calls.")
                .defineInRange("voiceRadius", 32, 1, 96);
        pokemonApproachChance = config.comment("Chance that an eligible wild Cobblemon approaches after a species call. Zero keeps the glance only.")
                .defineInRange("wildPokemonApproachChance", 0.20, 0.0, 1.0);
        hordeEnabled = config.comment("Offer voluntary stay-quiet encounters to players on this server.")
                .define("hordeEnabled", false);
        hordeCooldown = config.comment("Minimum seconds between encounters for the same player.")
                .defineInRange("hordeCooldownSeconds", 45, 10, 600);
        hordeCap = config.comment("Maximum zombies in one encounter, additionally limited by difficulty.")
                .defineInRange("hordeGroupCap", 4, 1, 4);
        SERVER_CONFIG = config.build();
    }
    // The physical client entry point supplies the receiver. Dedicated servers load no audio classes.
    public static Consumer<Packets.Down> playback = frame -> {};
    public static Consumer<MicPresence.Nearby> presence = packet -> {};
    public static Consumer<Boolean> hordeStatus = optedIn -> {};

    public static boolean hordeAvailable() { return SERVER_CONFIG.isLoaded() && hordeEnabled.getAsBoolean(); }
    public static double wildPokemonApproachChance() { return pokemonApproachChance.getAsDouble(); }

    public static HordeMode.Rules hordeRules() {
        return new HordeMode.Rules(hordeAvailable(), hordeCooldown.getAsInt() * 20, hordeCap.getAsInt());
    }

    public MobsEar(IEventBus bus, ModContainer container) {
        PokemonReactions.register();
        NamedMobReactions.register();
        container.registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG);
        bus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> {
            NearbyMics.tick(event.getServer(), radius.getAsInt());
            HordeMode.LIVE.tick(hordeRules(), radius.getAsInt(), event.getServer().getTickCount(), p -> hordeReply(p, false));
        });
        NeoForge.EVENT_BUS.addListener(HordeMode::restrictTarget);
        NeoForge.EVENT_BUS.addListener(HordeMode::loaded);
        NeoForge.EVENT_BUS.addListener(HordeMode::drops);
        NeoForge.EVENT_BUS.addListener(HordeMode::experience);
        NeoForge.EVENT_BUS.addListener(HordeMode::convert);
        NeoForge.EVENT_BUS.addListener(HordeMode::travel);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> forget(event, false));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> forget(event, true));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.Clone event) -> {
            if (event.getOriginal() instanceof ServerPlayer oldPlayer) ServerVoice.forget(oldPlayer);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) hordeReply(player, false);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            HordeMode.LIVE.clear();
            lastHordeRequest.clear();
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> ServerVoice.clear());
    }

    private static void forget(PlayerEvent event, boolean notify) {
        if (event.getEntity() instanceof ServerPlayer player) {
            boolean opted = HordeMode.LIVE.optedIn(player);
            ServerVoice.forget(player);
            lastHordeRequest.remove(player);
            if (notify && opted) hordeReply(player, false);
        }
    }

    private static void hordeReply(ServerPlayer player, boolean opted) {
        if (player.connection != null && player.connection.hasChannel(Packets.HordeState.TYPE)) {
            PacketDistributor.sendToPlayer(player, new Packets.HordeState(opted));
        }
    }

    private void register(RegisterPayloadHandlersEvent event) {
        var reg = event.registrar("1").optional();
        reg.playToServer(Packets.EntityCall.TYPE, Packets.EntityCall.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player)
                ServerVoice.entityCue(player, packet.entityType(), radius.getAsInt());
        });
        if (ModList.get().isLoaded("cobblemon")) {
            reg.playToServer(Packets.SpeciesCall.TYPE, Packets.SpeciesCall.CODEC, (packet, context) -> {
                if (context.player() instanceof ServerPlayer player)
                    ServerVoice.speciesCue(player, packet.species(), radius.getAsInt());
            });
        }
        reg.playToServer(Packets.HordeOpt.TYPE, Packets.HordeOpt.CODEC, (packet, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                if (packet.optedIn()) {
                    int tick = player.serverLevel().getServer().getTickCount();
                    Integer last = lastHordeRequest.get(player);
                    if (last != null && tick - last < 5) return;
                    lastHordeRequest.put(player, tick);
                }
                boolean before = HordeMode.LIVE.optedIn(player);
                boolean accepted = HordeMode.LIVE.consent(player, packet.optedIn(), hordeRules());
                if (before != accepted || accepted != packet.optedIn()) hordeReply(player, accepted);
            }
        });
        reg.playToClient(Packets.HordeState.TYPE, Packets.HordeState.CODEC, (packet, ctx) -> hordeStatus.accept(packet.optedIn()));
        reg.playToServer(MicPresence.Controls.TYPE, MicPresence.Controls.CODEC, (packet, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) NearbyMics.controls(player, packet);
        });
        reg.playToClient(MicPresence.Nearby.TYPE, MicPresence.Nearby.CODEC, (packet, ctx) -> presence.accept(packet));
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

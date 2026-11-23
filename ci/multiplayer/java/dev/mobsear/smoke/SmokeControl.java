package dev.mobsear.smoke;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** CI-only ordering barrier on each player's existing Minecraft connection. */
@Mod("mobsear")
public final class SmokeControl {
    static final String SPEAKER = "MicFixtureA";
    static final String LISTENER = "MicFixtureB";
    static final int READY = -1;
    static final int VISUAL = -2, FAR = -3, TEAM_VISIBLE = -4, TEAM_HIDDEN = -5;
    static final int LISTED_GUESTS = 12;
    static final String[] PHASES = {"near", "far", "nether", "near-again", "after-settings"};
    static IntConsumer client = phase -> { throw failure("client fixture unavailable"); };

    record Barrier(int phase) implements CustomPacketPayload {
        static final Type<Barrier> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mobsear", "ci_barrier"));
        static final StreamCodec<FriendlyByteBuf, Barrier> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeInt(value.phase()), buf -> new Barrier(buf.readInt()));
        @Override public Type<Barrier> type() { return TYPE; }
    }

    private final Set<UUID> ready = new HashSet<>();
    private final List<UUID> listedGuests = new ArrayList<>();
    private int phase = READY;
    private boolean speakerAck;
    private final Set<UUID> visualReady = new HashSet<>();

    public SmokeControl(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) ->
                event.registrar("1").playBidirectional(Barrier.TYPE, Barrier.CODEC, (packet, context) -> {
                    if (context.player() instanceof ServerPlayer player) receive(player, packet.phase());
                    else client.accept(packet.phase());
                }));
    }

    private void receive(ServerPlayer player, int value) {
        if (DistractionSmoke.handles(value)) { DistractionSmoke.photographed(player,value); return; }
        if (GazeSmoke.handles(value)) { GazeSmoke.acknowledge(player, value); return; }
        var server = player.serverLevel().getServer();
        var speaker = server.getPlayerList().getPlayerByName(SPEAKER);
        var listener = server.getPlayerList().getPlayerByName(LISTENER);
        if (value == PitchSmoke.NEAR || value == PitchSmoke.FAR) {
            PitchSmoke.receive(player, value);
            return;
        }
        if (value <= HordeSmoke.DENIED && value >= HordeSmoke.LEAVE) {
            HordeSmoke.acknowledge(player, value);
            return;
        }
        if (value == READY) {
            if ((player != speaker && player != listener) || !ready.add(player.getUUID())) {
                throw failure("unrecognized or duplicate client");
            }
            if (ready.size() == 2) {
                if (speaker == null || listener == null || speaker.getUUID().equals(listener.getUUID())) {
                    throw failure("expected two distinct connected players");
                }
                next(speaker, listener);
            }
            return;
        }
        if (value != phase || speaker == null || listener == null) throw failure("invalid phase acknowledgment");
        if (phase <= VISUAL) {
            if ((player != speaker && player != listener) || !visualReady.add(player.getUUID())) {
                throw failure("unexpected visual acknowledgment");
            }
            if (visualReady.size() == 2) {
                visualReady.clear();
                if (phase == TEAM_HIDDEN) HordeSmoke.start(speaker, listener);
                else beginVisual(speaker, listener, phase - 1);
            }
            return;
        }
        if (player == speaker && !speakerAck) {
            // The preceding Up packet and this acknowledgment traveled on one ordered connection.
            // The server has queued any eligible Down before queuing this listener-side barrier.
            speakerAck = true;
            PacketDistributor.sendToPlayer(listener, new Barrier(phase));
        } else if (player == listener && speakerAck) {
            System.out.println("MOBSEAR_SMOKE_PHASE " + PHASES[phase] + " passed");
            if (phase + 1 == PHASES.length) beginVisual(speaker, listener, VISUAL);
            else next(speaker, listener);
        } else throw failure("unexpected acknowledgment owner or order");
    }

    private void next(ServerPlayer speaker, ServerPlayer listener) {
        phase++;
        speakerAck = false;
        var server = speaker.serverLevel().getServer();
        ServerLevel overworld = server.overworld();
        if (phase == 0) place(speaker, overworld, 0);
        ServerLevel destination = phase == 2 ? server.getLevel(Level.NETHER) : overworld;
        if (destination == null) throw failure("missing test dimension");
        place(listener, destination, phase == 1 ? 48 : 8);
        if (phase == 3) {
            var extras = new ArrayList<ServerPlayer>();
            for (int i = 1; i <= LISTED_GUESTS; i++) {
                String name = guestName(i);
                UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                extras.add(new FakePlayer(overworld, new GameProfile(id, name)));
                listedGuests.add(id);
            }
            listener.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(extras));
        } else if (phase == 4) {
            listener.connection.send(new ClientboundPlayerInfoRemovePacket(listedGuests));
        }
        PacketDistributor.sendToPlayer(speaker, new Barrier(phase));
    }

    static String guestName(int number) { return String.format(Locale.ROOT, "VoiceGuest%02d", number); }

    private void beginVisual(ServerPlayer speaker, ServerPlayer listener, int next) {
        phase = next;
        var world = speaker.serverLevel();
        world.setDayTime(1000);
        int y = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 80);
        if (next != TEAM_HIDDEN) {
            speaker.teleportTo(world, 0.5, y, 80.5, -90, -3);
            listener.teleportTo(world, next == FAR ? 34.5 : 4.5, y, 80.5, 90, next == FAR ? 0 : -3);
        }
        if (next == TEAM_VISIBLE) {
            var board = world.getServer().getScoreboard();
            var team = board.addPlayerTeam("FixtureVoice");
            board.addPlayerToTeam(SPEAKER, team);
        } else if (next == TEAM_HIDDEN) {
            var team = world.getServer().getScoreboard().getPlayerTeam("FixtureVoice");
            team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
        }
        PacketDistributor.sendToPlayer(speaker, new Barrier(next));
        PacketDistributor.sendToPlayer(listener, new Barrier(next));
    }

    private static void place(ServerPlayer player, ServerLevel level, int x) {
        var feet = new BlockPos(x, 96, 0);
        level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
        player.teleportTo(level, x + 0.5, 96, 0.5, 0, 0);
    }

    static byte[] generatedFrame(int phase) {
        byte[] data = new byte[dev.mobsear.net.Packets.FRAME_BYTES];
        for (int i = 0; i < data.length / 2; i++) {
            int sample = ((i + 3 * phase) % 16 - 8) * (273 + phase);
            data[i * 2] = (byte) sample;
            data[i * 2 + 1] = (byte) (sample >> 8);
        }
        return data;
    }

    static IllegalStateException failure(String detail) {
        System.err.println("MOBSEAR_SMOKE_FAIL " + detail);
        return new IllegalStateException(detail);
    }
}

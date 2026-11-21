package dev.mobsear.smoke;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** CI-only ordering barrier on each player's existing Minecraft connection. */
@Mod("mobsear")
public final class SmokeControl {
    static final String SPEAKER = "MicFixtureA";
    static final String LISTENER = "MicFixtureB";
    static final int READY = -1;
    static final String[] PHASES = {"near", "far", "nether", "near-again"};
    static IntConsumer client = phase -> { throw failure("client fixture unavailable"); };

    record Barrier(int phase) implements CustomPacketPayload {
        static final Type<Barrier> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mobsear", "ci_barrier"));
        static final StreamCodec<FriendlyByteBuf, Barrier> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeInt(value.phase()), buf -> new Barrier(buf.readInt()));
        @Override public Type<Barrier> type() { return TYPE; }
    }

    private final Set<UUID> ready = new HashSet<>();
    private int phase = READY;
    private boolean speakerAck;

    public SmokeControl(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) ->
                event.registrar("1").playBidirectional(Barrier.TYPE, Barrier.CODEC, (packet, context) -> {
                    if (context.player() instanceof ServerPlayer player) receive(player, packet.phase());
                    else client.accept(packet.phase());
                }));
    }

    private void receive(ServerPlayer player, int value) {
        var server = player.serverLevel().getServer();
        var speaker = server.getPlayerList().getPlayerByName(SPEAKER);
        var listener = server.getPlayerList().getPlayerByName(LISTENER);
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
        if (player == speaker && !speakerAck) {
            // The preceding Up packet and this acknowledgment traveled on one ordered connection.
            // The server has queued any eligible Down before queuing this listener-side barrier.
            speakerAck = true;
            PacketDistributor.sendToPlayer(listener, new Barrier(phase));
        } else if (player == listener && speakerAck) {
            System.out.println("MOBSEAR_SMOKE_PHASE " + PHASES[phase] + " passed");
            if (phase + 1 == PHASES.length) System.out.println("MOBSEAR_MULTIPLAYER_PASS");
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
        PacketDistributor.sendToPlayer(speaker, new Barrier(phase));
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

package dev.mobsear.server;

import com.mojang.authlib.GameProfile;
import dev.mobsear.net.Packets;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The relay receives the same speaker object the live handler obtains from its payload context.
 * The test substitutes an in-memory sink for the final network send and exercises both wire codecs. */
@GameTestHolder("mobsear")
@PrefixGameTestTemplate(false)
public final class VoiceRoutingGameTest {
    @GameTest(template = "hearing_floor")
    public static void serverRoutesSyntheticVoice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Test server needs its Nether level");
        Vec3 origin = helper.absoluteVec(new Vec3(4.5, 2, 4.5));
        double radius = 8;
        ServerPlayer speaker = player(level, "Speaker", origin);
        ServerPlayer nearby = player(level, "Nearby", origin.add(2, 0, 0));
        ServerPlayer far = player(level, "Far", origin.add(0, 0, 3 * radius));
        ServerPlayer otherWorld = player(nether, "OtherWorld", origin);
        List<ServerPlayer> candidates = List.of(speaker, nearby, far, otherWorld);
        List<ServerPlayer> recipients = new ArrayList<>();
        List<Packets.Down> delivered = new ArrayList<>();
        byte[] samples = syntheticWave();
        Packets.Up ingress = roundTrip(Packets.Up.CODEC, new Packets.Up(samples));
        ServerVoice.relay(speaker, ingress, candidates, radius, (listener, payload) -> {
            recipients.add(listener);
            delivered.add(roundTrip(Packets.Down.CODEC, payload));
        });
        helper.assertTrue(recipients.equals(List.of(nearby)), "Only the nearby listener should hear this frame");
        Packets.Down audible = delivered.getFirst();
        helper.assertTrue(audible.speaker().equals(speaker.getUUID()), "Relayed voice has the wrong owner");
        helper.assertTrue(audible.dimension().equals(level.dimension().location()), "Wrong speaker dimension");
        helper.assertTrue(Math.abs(audible.gain() - 0.75f) < 0.0001f, "Wrong two-block gain at radius eight");
        helper.assertTrue(Arrays.equals(audible.pcm(), samples), "Synthetic PCM changed in transit");

        // Reuse the same wire payload. Only the authoritative speaker's position changes.
        speaker.setPos(far.position());
        recipients.clear();
        ServerVoice.relay(speaker, ingress, candidates, radius, (listener, payload) -> {
            recipients.add(listener);
            helper.assertTrue(payload.gain() == 1f, "Co-located listener should hear full gain");
        });
        helper.assertTrue(recipients.equals(List.of(far)), "Routing did not follow the actual speaker");
        helper.succeed();
    }

    private static ServerPlayer player(ServerLevel level, String name, Vec3 position) {
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(position);
        return player;
    }

    private static byte[] syntheticWave() {
        byte[] samples = new byte[Packets.FRAME_BYTES];
        for (int i = 0; i < samples.length / 2; i++) {
            int sample = i % 16 < 8 ? 0x1234 : -0x4321;
            samples[2 * i] = (byte) sample;
            samples[2 * i + 1] = (byte) (sample >> 8);
        }
        return samples;
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(wire, value);
            T decoded = codec.decode(wire);
            if (wire.isReadable()) {
                throw new IllegalStateException("Trailing bytes after decoding voice payload");
            }
            return decoded;
        } finally {
            wire.release();
        }
    }
}

package dev.mobsear.server;

import com.mojang.authlib.GameProfile;
import dev.mobsear.net.MicPresence;
import dev.mobsear.net.MicState;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The relay receives the same speaker object the live handler obtains from its payload context.
 * The test substitutes an in-memory sink for the final network send and exercises both wire codecs. */
@GameTestHolder("mobsear")
@PrefixGameTestTemplate(false)
public final class VoiceRoutingGameTest {
    @GameTest(template = "hearing_floor")
    public static void numberedMobCallWire(GameTestHelper helper) {
        var entity = ResourceLocation.withDefaultNamespace("spider");
        var species = ResourceLocation.fromNamespaceAndPath("cobblemon", "lopunny");
        for (var call : List.<Packets.MobCall>of(new Packets.Call(dev.mobsear.net.Cue.MOBS),
                new Packets.EntityCall(entity), new Packets.SpeciesCall(species))) {
            var request = new Packets.CallRequest(Long.MAX_VALUE, call);
            helper.assertTrue(request.equals(roundTrip(Packets.CallRequest.CODEC, request)), "Changed numbered mob call");
        }
        var reply = new Packets.Heard(8192, species, true);
        helper.assertTrue(reply.equals(roundTrip(Packets.Heard.CODEC, reply)), "Changed private reply");
        for (long invalid : new long[]{0, -1}) {
            try {
                roundTrip(Packets.CallRequest.CODEC, new Packets.CallRequest(invalid, new Packets.EntityCall(entity)));
                helper.fail("Accepted invalid request number");
            } catch (io.netty.handler.codec.DecoderException expected) { }
            try {
                roundTrip(Packets.Heard.CODEC, new Packets.Heard(invalid, entity, false));
                helper.fail("Accepted invalid confirmation number");
            } catch (io.netty.handler.codec.DecoderException expected) { }
        }
        helper.succeed();
    }

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
        speaker.setPos(origin);
        List<MicPresence.Nearby> snapshots = new ArrayList<>();
        var selected = new MicPresence.Controls(MicPresence.styleId("emo"), false, false);
        NearbyMics.controls(speaker, roundTrip(MicPresence.Controls.CODEC, selected));
        // The client can advertise its style but cannot declare itself speaking.
        NearbyMics.distribute(speaker, candidates, (int) radius, (viewer, status) -> {
            helper.assertTrue(viewer == nearby, "Presence leaked out of range or into another dimension");
            snapshots.add(roundTrip(MicPresence.Nearby.CODEC, status));
        });
        helper.assertTrue(snapshots.size() == 1 && snapshots.getFirst().state() == MicState.IDLE,
                "Advertising a microphone must not create speaking activity");
        ServerVoice.relay(speaker, ingress, candidates, radius, (viewer, audio) -> {});
        snapshots.clear();
        NearbyMics.distribute(speaker, candidates, (int) radius, (viewer, status) -> snapshots.add(status));
        var active = snapshots.getFirst();
        helper.assertTrue(active.state() == MicState.SPEAKING && active.level() > 0 && active.style() == selected.style()
                && active.speaker().equals(speaker.getUUID()) && active.radius() == (int) radius,
                "Presence must come from accepted audio and authoritative speaker data");
        NearbyMics.controls(nearby, new MicPresence.Controls(0, false, true));
        ServerVoice.relay(speaker, ingress, candidates, radius,
                (viewer, audio) -> helper.fail("A deafened receiver heard a frame"));
        ServerVoice.relay(nearby, ingress, candidates, radius,
                (viewer, audio) -> helper.fail("A deafened speaker transmitted a frame"));
        NearbyMics.forget(nearby);
        NearbyMics.controls(nearby, new MicPresence.Controls(0, true, false));
        ServerVoice.relay(nearby, ingress, candidates, radius,
                (viewer, audio) -> helper.fail("A muted speaker transmitted a frame"));
        recipients.clear();
        ServerVoice.relay(speaker, ingress, candidates, radius, (viewer, audio) -> recipients.add(viewer));
        helper.assertTrue(recipients.equals(List.of(nearby)), "A mic-muted player should still hear proximity audio");
        ServerVoice.forget(speaker);
        ServerVoice.forget(nearby);
        helper.succeed();
    }

    @GameTest(template = "hearing_floor")
    public static void teamNameTagPrivacy(GameTestHelper helper) {
        var level = helper.getLevel();
        var center = helper.absoluteVec(new Vec3(4.5, 2, 4.5));
        var speaker = player(level, "TagSpeaker", center);
        var ally = player(level, "TagAlly", center);
        var rival = player(level, "TagRival", center);
        var unteamed = player(level, "TagNeutral", center);
        var candidates = List.of(ally, rival, unteamed);
        var board = level.getScoreboard();
        var friends = board.addPlayerTeam("ear_tag_friends");
        var opponents = board.addPlayerTeam("ear_tag_rivals");
        try {
            board.addPlayerToTeam(speaker.getScoreboardName(), friends);
            board.addPlayerToTeam(ally.getScoreboardName(), friends);
            board.addPlayerToTeam(rival.getScoreboardName(), opponents);
            NearbyMics.controls(speaker, new MicPresence.Controls(0, false, false));
            for (var rule : Team.Visibility.values()) {
                friends.setNameTagVisibility(rule);
                var recipients = new ArrayList<ServerPlayer>();
                NearbyMics.distribute(speaker, candidates, 8, (viewer, status) -> recipients.add(viewer));
                List<ServerPlayer> expected = switch (rule) {
                    case ALWAYS -> candidates;
                    case NEVER -> List.of();
                    case HIDE_FOR_OTHER_TEAMS -> List.of(ally, unteamed);
                    case HIDE_FOR_OWN_TEAM -> List.of(rival, unteamed);
                };
                helper.assertTrue(recipients.equals(expected), "Mic status ignored team name-tag rule " + rule);
            }
            friends.setNameTagVisibility(Team.Visibility.ALWAYS);
            speaker.setInvisible(true);
            friends.setSeeFriendlyInvisibles(false);
            NearbyMics.distribute(speaker, candidates, 8, (v, status) -> helper.fail("Invisible tag leaked mic status"));
            friends.setSeeFriendlyInvisibles(true);
            var visible = new ArrayList<ServerPlayer>();
            NearbyMics.distribute(speaker, candidates, 8, (v, status) -> visible.add(v));
            helper.assertTrue(visible.equals(List.of(ally)), "Friendly invisible tag visibility");
        } finally {
            NearbyMics.forget(speaker);
            board.removePlayerTeam(friends);
            board.removePlayerTeam(opponents);
        }
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

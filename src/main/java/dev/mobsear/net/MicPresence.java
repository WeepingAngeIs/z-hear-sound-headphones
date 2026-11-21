package dev.mobsear.net;

import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/** A client can publish only its own selected cosmetic and two control flags. */
public final class MicPresence {
    private static final List<String> STYLES = List.of("uwu", "emo", "vanilla", "amethyst");
    private MicPresence() {}

    public static int styleId(String id) { return Math.max(0, STYLES.indexOf(id)); }
    public static String styleName(int id) { return STYLES.get(id); }

    /** Team name-tag rules apply at both ends, including to an already cached snapshot. */
    public static boolean nameVisible(Player speaker, Player viewer) {
        int distance = speaker.isDiscrete() ? 32 : 64;
        if (speaker.distanceToSqr(viewer) >= distance * distance) return false;
        boolean visible = !speaker.isInvisibleTo(viewer);
        var team = speaker.getTeam();
        if (team == null) return visible && !speaker.isVehicle();
        var viewersTeam = viewer.getTeam();
        return switch (team.getNameTagVisibility()) {
            case ALWAYS -> visible;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> viewersTeam == null ? visible
                    : team.isAlliedTo(viewersTeam) && (team.canSeeFriendlyInvisibles() || visible);
            case HIDE_FOR_OWN_TEAM -> visible && (viewersTeam == null || !team.isAlliedTo(viewersTeam));
        };
    }

    public record Controls(int style, boolean muted, boolean deafened) implements CustomPacketPayload {
        public static final Type<Controls> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mobsear", "mic_controls"));
        public static final StreamCodec<FriendlyByteBuf, Controls> CODEC = StreamCodec.of((b, v) ->
                b.writeByte(v.style | (v.muted ? 4 : 0) | (v.deafened ? 8 : 0)), b -> {
                    if (b.readableBytes() != 1) throw new DecoderException("Invalid microphone control length");
                    int flags = b.readUnsignedByte();
                    if ((flags & ~15) != 0) throw new DecoderException("Unknown microphone controls");
                    return new Controls(flags & 3, (flags & 4) != 0, (flags & 8) != 0);
                });
        @Override public Type<Controls> type() { return TYPE; }
    }

    public record Nearby(UUID speaker, ResourceLocation dimension, int radius, int style, MicState state, int level)
            implements CustomPacketPayload {
        public static final Type<Nearby> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mobsear", "nearby_mic"));
        public static final StreamCodec<FriendlyByteBuf, Nearby> CODEC = StreamCodec.of((b, v) -> {
            b.writeUUID(v.speaker);
            b.writeResourceLocation(v.dimension);
            b.writeByte(v.radius);
            b.writeByte(v.style);
            b.writeByte(v.state.ordinal());
            b.writeByte(v.level);
        }, b -> {
            UUID id = b.readUUID();
            ResourceLocation dimension = b.readResourceLocation();
            if (b.readableBytes() != 4) throw new DecoderException("Invalid microphone status length");
            int radius = b.readUnsignedByte(), style = b.readUnsignedByte(), state = b.readUnsignedByte();
            if (radius < 1 || radius > 96 || style >= STYLES.size() || state >= MicState.values().length)
                throw new DecoderException("Invalid microphone status");
            return new Nearby(id, dimension, radius, style, MicState.values()[state], b.readUnsignedByte());
        });
        @Override public Type<Nearby> type() { return TYPE; }
    }
}

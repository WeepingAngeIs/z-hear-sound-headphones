package dev.mobsear.net;

import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class Packets {
    public static final int FRAME_BYTES = 640;
    public static final int MAX_CALL_ID_LENGTH = 128;

    private Packets() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("mobsear", path));
    }

    private static byte[] readFrame(FriendlyByteBuf buf) {
        if (buf.readableBytes() != FRAME_BYTES) throw new DecoderException("Invalid voice frame length");
        byte[] frame = new byte[FRAME_BYTES];
        buf.readBytes(frame);
        return frame;
    }

    private static boolean readFlag(FriendlyByteBuf buf) {
        if (buf.readableBytes() != 1) throw new DecoderException("Invalid horde option length");
        int flag = buf.readUnsignedByte();
        if (flag > 1) throw new DecoderException("Invalid horde option");
        return flag == 1;
    }

    public record HordeOpt(boolean optedIn) implements CustomPacketPayload {
        public static final Type<HordeOpt> TYPE = id("horde_opt");
        public static final StreamCodec<FriendlyByteBuf, HordeOpt> CODEC =
                StreamCodec.of((buf, packet) -> buf.writeBoolean(packet.optedIn), buf -> new HordeOpt(readFlag(buf)));
        @Override public Type<HordeOpt> type() { return TYPE; }
    }

    public record HordeState(boolean optedIn) implements CustomPacketPayload {
        public static final Type<HordeState> TYPE = id("horde_state");
        public static final StreamCodec<FriendlyByteBuf, HordeState> CODEC =
                StreamCodec.of((buf, packet) -> buf.writeBoolean(packet.optedIn), buf -> new HordeState(readFlag(buf)));
        @Override public Type<HordeState> type() { return TYPE; }
    }

    public record Up(byte[] pcm) implements CustomPacketPayload {
        public static final Type<Up> TYPE = id("voice_up");
        public static final StreamCodec<FriendlyByteBuf, Up> CODEC =
                StreamCodec.of((buf, packet) -> buf.writeBytes(packet.pcm), buf -> new Up(readFrame(buf)));
        @Override public Type<Up> type() { return TYPE; }
    }

    public record Down(UUID speaker, ResourceLocation dimension, float gain, byte[] pcm) implements CustomPacketPayload {
        public static final Type<Down> TYPE = id("voice_down");
        public static final StreamCodec<FriendlyByteBuf, Down> CODEC = StreamCodec.of((buf, packet) -> {
            buf.writeUUID(packet.speaker);
            buf.writeResourceLocation(packet.dimension);
            buf.writeFloat(packet.gain);
            buf.writeBytes(packet.pcm);
        }, buf -> new Down(buf.readUUID(), buf.readResourceLocation(), buf.readFloat(), readFrame(buf)));
        @Override public Type<Down> type() { return TYPE; }
    }

    private static ResourceLocation readCallId(FriendlyByteBuf buf) {
        String key = buf.readUtf(MAX_CALL_ID_LENGTH);
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null || id.getPath().isEmpty() || !key.equals(id.toString()) || buf.isReadable())
            throw new DecoderException("Invalid call identifier");
        return id;
    }

    /** Private reply to the caller, emitted only when a server-side reaction starts. */
    public record Heard(ResourceLocation id, boolean species) implements CustomPacketPayload {
        public static final Type<Heard> TYPE = Packets.id("heard");
        public static final StreamCodec<FriendlyByteBuf, Heard> CODEC = StreamCodec.of((buf, packet) -> {
            buf.writeBoolean(packet.species);
            buf.writeUtf(packet.id.toString(), MAX_CALL_ID_LENGTH);
        }, buf -> {
            int kind = buf.readUnsignedByte();
            if (kind > 1) throw new DecoderException("Invalid heard mob kind");
            return new Heard(readCallId(buf), kind == 1);
        });
        @Override public Type<Heard> type() { return TYPE; }
    }

    public record EntityCall(ResourceLocation entityType) implements CustomPacketPayload {
        public static final Type<EntityCall> TYPE = id("entity_call");
        public static final StreamCodec<FriendlyByteBuf, EntityCall> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeUtf(packet.entityType.toString(), MAX_CALL_ID_LENGTH),
                buf -> new EntityCall(readCallId(buf)));
        @Override public Type<EntityCall> type() { return TYPE; }
    }

    public record SpeciesCall(ResourceLocation species) implements CustomPacketPayload {
        public static final int MAX_ID_LENGTH = MAX_CALL_ID_LENGTH;
        public static final Type<SpeciesCall> TYPE = id("species_call");
        public static final StreamCodec<FriendlyByteBuf, SpeciesCall> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeUtf(packet.species.toString(), MAX_ID_LENGTH),
                buf -> new SpeciesCall(readCallId(buf)));
        @Override public Type<SpeciesCall> type() { return TYPE; }
    }

    public record Call(Cue cue) implements CustomPacketPayload {
        public static final Type<Call> TYPE = id("mob_cue");
        public static final StreamCodec<FriendlyByteBuf, Call> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeByte(packet.cue.ordinal()), buf -> {
                    if (buf.readableBytes() != 1) throw new DecoderException("Invalid mob cue length");
                    int id = buf.readUnsignedByte();
                    if (id >= Cue.values().length) throw new DecoderException("Unknown mob cue");
                    return new Call(Cue.values()[id]);
                });
        @Override public Type<Call> type() { return TYPE; }
    }
}

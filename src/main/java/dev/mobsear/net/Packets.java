package dev.mobsear.net;

import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class Packets {
    public static final int FRAME_BYTES = 640;

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

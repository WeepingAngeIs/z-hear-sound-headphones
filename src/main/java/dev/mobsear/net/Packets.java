package dev.mobsear.net;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
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

    /** Requests the eligible species around the authenticated player, without supplying a position. */
    public record NearbySpeciesRequest() implements CustomPacketPayload {
        public static final Type<NearbySpeciesRequest> TYPE = id("nearby_species_request");
        public static final StreamCodec<FriendlyByteBuf, NearbySpeciesRequest> CODEC = StreamCodec.unit(new NearbySpeciesRequest());
        @Override public Type<NearbySpeciesRequest> type() { return TYPE; }
    }

    /** Only the caller needs to predict a visiting Pokémon's right-click interaction. */
    public record Visit(UUID pokemonEntity, boolean arrived) implements CustomPacketPayload {
        public static final int WINDOW_TICKS = 120;
        public static final Type<Visit> TYPE = id("pokemon_visit");
        public static final StreamCodec<FriendlyByteBuf, Visit> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.pokemonEntity);
            buf.writeBoolean(value.arrived);
        }, buf -> new Visit(buf.readUUID(), readFlag(buf)));
        @Override public Type<Visit> type() { return TYPE; }
    }

    /** Registry IDs only, ordered by distance to the nearest eligible individual of each species. */
    public record NearbySpecies(ResourceLocation dimension, List<ResourceLocation> species) implements CustomPacketPayload {
        public static final int MAX_SPECIES = 4096;
        public NearbySpecies { species = List.copyOf(species); }
        public static final Type<NearbySpecies> TYPE = id("nearby_species");
        public static final StreamCodec<FriendlyByteBuf, NearbySpecies> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeResourceLocation(value.dimension);
            buf.writeVarInt(value.species.size());
            for (var id : value.species) buf.writeUtf(id.toString(), MAX_CALL_ID_LENGTH);
        }, buf -> {
            var dimension = buf.readResourceLocation();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_SPECIES) throw new DecoderException("Invalid nearby species count");
            var species = new ArrayList<ResourceLocation>(count);
            for (int i = 0; i < count; i++) species.add(ResourceLocation.parse(buf.readUtf(MAX_CALL_ID_LENGTH)));
            if (buf.isReadable()) throw new DecoderException("Unexpected nearby species data");
            return new NearbySpecies(dimension, species);
        });
        @Override public Type<NearbySpecies> type() { return TYPE; }
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

    /** Transient model orientation for an already tracked mob. A zero lifetime releases the pose. */
    public record MobPose(int entityId, byte yaw, int ticks) implements CustomPacketPayload {
        public static final int MAX_TICKS = 7;
        public static final Type<MobPose> TYPE = id("mob_pose");
        public static final StreamCodec<FriendlyByteBuf, MobPose> CODEC = StreamCodec.of((buf, pose) -> {
            buf.writeVarInt(pose.entityId);
            buf.writeByte(pose.yaw);
            buf.writeByte(pose.ticks);
        }, buf -> {
            var pose = new MobPose(buf.readVarInt(), buf.readByte(), buf.readUnsignedByte());
            if (pose.ticks > MAX_TICKS || buf.isReadable()) throw new DecoderException("Invalid mob pose");
            return pose;
        });
        @Override public Type<MobPose> type() { return TYPE; }
    }

    public record CallDeclined(long requestId, CallFailure reason) implements CustomPacketPayload {
        public static final Type<CallDeclined> TYPE = id("call_declined");
        public static final StreamCodec<FriendlyByteBuf, CallDeclined> CODEC = StreamCodec.of((buf, packet) -> {
            buf.writeVarLong(packet.requestId);
            buf.writeByte(packet.reason.ordinal());
        }, buf -> {
            long number = readRequestId(buf);
            int index = buf.readUnsignedByte();
            var reasons = CallFailure.values();
            if (index >= reasons.length || buf.isReadable()) throw new DecoderException("Invalid call outcome");
            return new CallDeclined(number, reasons[index]);
        });
        @Override public Type<CallDeclined> type() { return TYPE; }
    }

    private static ResourceLocation readCallId(FriendlyByteBuf buf) {
        String key = buf.readUtf(MAX_CALL_ID_LENGTH);
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null || id.getPath().isEmpty() || !key.equals(id.toString()) || buf.isReadable())
            throw new DecoderException("Invalid call identifier");
        return id;
    }

    public sealed interface MobCall extends CustomPacketPayload permits Call, EntityCall, SpeciesCall {}

    private static long readRequestId(FriendlyByteBuf buf) {
        long value = buf.readVarLong();
        if (value <= 0) throw new DecoderException("Invalid mob call request number");
        return value;
    }

    /** A negotiated extension of the original calls; their codecs still enforce the same bounds. */
    public record CallRequest(long requestId, MobCall call) implements CustomPacketPayload {
        public static final Type<CallRequest> TYPE = Packets.id("call_request");
        public static final StreamCodec<FriendlyByteBuf, CallRequest> CODEC = StreamCodec.of((buf, packet) -> {
            buf.writeVarLong(packet.requestId);
            switch (packet.call) {
                case Call classic -> { buf.writeByte(0); Call.CODEC.encode(buf, classic); }
                case EntityCall entity -> { buf.writeByte(1); EntityCall.CODEC.encode(buf, entity); }
                case SpeciesCall species -> { buf.writeByte(2); SpeciesCall.CODEC.encode(buf, species); }
            }
        }, buf -> {
            long number = readRequestId(buf);
            MobCall call = switch (buf.readUnsignedByte()) {
                case 0 -> Call.CODEC.decode(buf);
                case 1 -> EntityCall.CODEC.decode(buf);
                case 2 -> SpeciesCall.CODEC.decode(buf);
                default -> throw new DecoderException("Invalid mob call kind");
            };
            return new CallRequest(number, call);
        });
        @Override public Type<CallRequest> type() { return TYPE; }
    }

    /** Private reply to the numbered request that started a server-side reaction. */
    public record Heard(long requestId, ResourceLocation id, boolean species) implements CustomPacketPayload {
        public static final Type<Heard> TYPE = Packets.id("heard_reply");
        public static final StreamCodec<FriendlyByteBuf, Heard> CODEC = StreamCodec.of((buf, packet) -> {
            buf.writeVarLong(packet.requestId);
            buf.writeBoolean(packet.species);
            buf.writeUtf(packet.id.toString(), MAX_CALL_ID_LENGTH);
        }, buf -> {
            long number = readRequestId(buf);
            int kind = buf.readUnsignedByte();
            if (kind > 1) throw new DecoderException("Invalid heard mob kind");
            return new Heard(number, readCallId(buf), kind == 1);
        });
        @Override public Type<Heard> type() { return TYPE; }
    }

    public record EntityCall(ResourceLocation entityType) implements MobCall {
        public static final Type<EntityCall> TYPE = id("entity_call");
        public static final StreamCodec<FriendlyByteBuf, EntityCall> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeUtf(packet.entityType.toString(), MAX_CALL_ID_LENGTH),
                buf -> new EntityCall(readCallId(buf)));
        @Override public Type<EntityCall> type() { return TYPE; }
    }

    public record SpeciesCall(ResourceLocation species) implements MobCall {
        public static final int MAX_ID_LENGTH = MAX_CALL_ID_LENGTH;
        public static final Type<SpeciesCall> TYPE = id("species_call");
        public static final StreamCodec<FriendlyByteBuf, SpeciesCall> CODEC = StreamCodec.of(
                (buf, packet) -> buf.writeUtf(packet.species.toString(), MAX_ID_LENGTH),
                buf -> new SpeciesCall(readCallId(buf)));
        @Override public Type<SpeciesCall> type() { return TYPE; }
    }

    public record Call(Cue cue) implements MobCall {
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

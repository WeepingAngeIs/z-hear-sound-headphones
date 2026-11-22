package dev.mobsear.server;

import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** CI-only ordering/control. Species identity is transmitted separately on the real mod connection. */
@Mod("mobsear")
public final class PokemonProbe {
    static final int HELLO=0, BEFORE=1, FIRE=2, SEEN=3, ARRANGE=4, VERIFIED_GUARD=5;
    static Consumer<Message> client;
    public record Message(int stage, int scenario, int entity) implements CustomPacketPayload {
        static final Type<Message> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mobsear","ci_pokemon"));
        static final StreamCodec<FriendlyByteBuf,Message> CODEC = StreamCodec.of((buf, m) -> {
            buf.writeByte(m.stage); buf.writeByte(m.scenario); buf.writeVarInt(m.entity);
        }, buf -> new Message(buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readVarInt()));
        @Override public Type<Message> type() { return TYPE; }
    }
    public PokemonProbe(IEventBus bus) {
        var server = new PokemonCaseServer();
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playBidirectional(
                Message.TYPE, Message.CODEC, (m, ctx) -> {
                    if (ctx.player() instanceof ServerPlayer p) server.handle(p,m);
                    else client.accept(m);
                }));
    }
    static IllegalStateException fail(String problem) {
        System.err.println("POKEMON_FIXTURE_FAIL " + problem);
        return new IllegalStateException(problem);
    }
}

package dev.mobsear.packprobe;

import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

/** Count normal game traffic received by the modded client while it actually feeds a visitor. */
final class PackBerryAudio {
    void attach(Minecraft mc, Supplier<PackProbe.Observation> observation) {
        mc.getConnection().getConnection().channel().pipeline().addBefore("packet_handler","pack_berry_audio",
                new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx,Object message) throws Exception {
                if (message instanceof BundlePacket<?> bundle) {
                    for (var packet:bundle.subPackets()) observe(packet);
                } else observe(message);
                super.channelRead(ctx,message);
            }

            private void observe(Object message) {
                if (message instanceof ClientboundSoundPacket packet
                        && packet.getSound().value().getLocation().toString().equals("minecraft:entity.generic.eat"))
                    mc.execute(() -> {
                        var trial = observation.get();
                        if (trial==null || trial.feeding==null || mc.level==null) return;
                        var visitor = mc.level.getEntity(trial.entity);
                        if (visitor!=null && visitor.distanceToSqr(packet.getX(),packet.getY(),packet.getZ())<9)
                            trial.eatSounds++;
                    });
                if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload() instanceof PlayPosableAnimationPacket cry && cry.getAnimation().contains("cry"))
                    mc.execute(() -> {
                        var trial = observation.get();
                        if (trial!=null && trial.feeding!=null && trial.entity==cry.getEntityId()) trial.completionCries++;
                    });
            }
        });
    }
}

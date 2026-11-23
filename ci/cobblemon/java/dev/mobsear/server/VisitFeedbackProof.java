package dev.mobsear.server;

import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

/** Read the actual incoming game effects on each client. All clicks, packets and renders still run normally. */
final class VisitFeedbackProof {
    private int entity=-1, hearts, cries, crumbs, bites, noms;
    private int heartFrame, berryFrame;
    private boolean active;
    void attach(Minecraft mc) {
        var channel=mc.getConnection().getConnection().channel();
        channel.pipeline().addBefore("packet_handler","mobsear_visit_proof",new ChannelInboundHandlerAdapter(){
            @Override public void channelRead(ChannelHandlerContext ctx,Object msg) throws Exception {
                if (msg instanceof BundlePacket<?> bundle) for (var part:bundle.subPackets()) listen(mc,part);
                else listen(mc,msg);
                super.channelRead(ctx,msg);
            }
        });
    }
    private void listen(Minecraft mc,Object msg) {
        if (msg instanceof ClientboundCustomPayloadPacket p && p.payload() instanceof PlayPosableAnimationPacket a
                && a.getAnimation().contains("cry"))
            mc.execute(()->{ if (active && a.getEntityId()==entity) cries++; });
        if (msg instanceof ClientboundEntityEventPacket p && p.getEventId()==45)
            mc.execute(()->{ if (active && mc.level!=null && p.getEntity(mc.level)!=null && p.getEntity(mc.level).getId()==entity) bites++; });
        if (msg instanceof ClientboundSoundPacket p && p.getSound().value().getLocation().toString().equals("mobsear:nom_nom"))
            mc.execute(()->{ if (active) noms++; });
        if (msg instanceof ClientboundLevelParticlesPacket p) {
            if (p.getParticle().getType()==ParticleTypes.HEART) mc.execute(()->{ if (active) hearts+=p.getCount(); });
            if (p.getParticle().getType()==ParticleTypes.ITEM) mc.execute(()->{ if (active) crumbs+=p.getCount(); });
        }
    }
    void stage(PokemonCase scenario,int id) {
        if (active && scenario!=PokemonCase.WALKING_PIKACHU) {
            if (hearts!=4 || cries<1 || noms!=2 || bites<2 || crumbs<8)
                throw PokemonProbe.fail("Missing client visit effects hearts="+hearts+" cries="+cries+" nom="+noms+" bites="+bites+" crumbs="+crumbs);
            System.out.println("MOBSEAR_POKEMON_CLIENT_CARE actualHearts="+hearts+" speciesCry="+cries+" nomSounds="+noms+" eatingEvents="+bites+" berryCrumbs="+crumbs);
            active=false;
        }
        if (scenario==PokemonCase.WALKING_PIKACHU) { entity=id; active=true; }
    }
    void frame(Minecraft mc,String role) {
        if (!active) return;
        String label=null;
        if (bites>0 && ++berryFrame==4) label="feeding";
        if (hearts>0 && ++heartFrame==4) label="head-pat";
        if (label==null) return;
        try (var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var path=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"),"evidence",role+"-visit-"+label+".png");
            image.writeToFile(path);
        } catch (Exception ex) {throw PokemonProbe.fail("Could not save visit view "+ex.getClass().getSimpleName());}
    }
}

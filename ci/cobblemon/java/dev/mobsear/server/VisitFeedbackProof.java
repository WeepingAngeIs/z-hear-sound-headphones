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
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

/** Read the actual incoming game effects on each client. All clicks, packets and renders still run normally. */
final class VisitFeedbackProof {
    private int entity=-1, hearts, cries, crumbs, bites, noms, props, completed;
    private int propId=-1;
    private boolean awaitingCry;
    private float minPitch=90, maxPitch=-90;
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
            mc.execute(()->{ if (active && a.getEntityId()==entity) { cries++; if (awaitingCry) { completed++; awaitingCry=false; } } });
        if (msg instanceof ClientboundAddEntityPacket p && p.getType()==EntityType.ITEM_DISPLAY)
            mc.execute(()->{ if (active) { propId=p.getId(); props++; minPitch=90; maxPitch=-90; } });
        if (msg instanceof ClientboundRemoveEntitiesPacket p)
            mc.execute(()->{ if (active && propId>=0 && p.getEntityIds().contains(propId)) { propId=-1; awaitingCry=true; } });
        if (msg instanceof ClientboundSoundPacket p && p.getSound().value().getLocation().toString().equals("minecraft:entity.generic.eat"))
            mc.execute(()->{ if (active && propId>=0) bites++; });
        if (msg instanceof ClientboundSoundPacket p && p.getSound().value().getLocation().toString().equals("mobsear:nom_nom"))
            mc.execute(()->{ if (active) noms++; });
        if (msg instanceof ClientboundLevelParticlesPacket p) {
            if (p.getParticle().getType()==ParticleTypes.HEART) mc.execute(()->{ if (active) hearts+=p.getCount(); });
            if (p.getParticle().getType()==ParticleTypes.ITEM) mc.execute(()->{ if (active) crumbs+=p.getCount(); });
        }
    }
    void stage(PokemonCase scenario,int id) {
        if (active && scenario!=PokemonCase.WALKING_PIKACHU) {
            if (hearts!=4 || cries<3 || noms!=0 || props!=2 || completed!=2 || bites<4 || crumbs<8)
                throw PokemonProbe.fail("Missing client visit effects hearts="+hearts+" cries="+cries+" nom="+noms+" bites="+bites+" crumbs="+crumbs);
            System.out.println("MOBSEAR_POKEMON_CLIENT_CARE actualHearts="+hearts+" speciesCry="+cries+" nomSounds="+noms+" eatingEvents="+bites+" berryCrumbs="+crumbs+" props="+props+" completed="+completed);
            active=false;
        }
        if (scenario==PokemonCase.WALKING_PIKACHU) { entity=id; active=true; }
    }
    void frame(Minecraft mc,String role) {
        if (!active) return;
        String label=null;
        if (propId>=0 && mc.level!=null && mc.level.getEntity(entity)!=null) {
            float pitch=mc.level.getEntity(entity).getXRot();
            minPitch=Math.min(minPitch,pitch); maxPitch=Math.max(maxPitch,pitch);
            if (maxPitch-minPitch>5 && berryFrame<props) { berryFrame=props; label="feeding-"+props; }
        }
        if (hearts>0 && ++heartFrame==4) label="head-pat";
        if (label==null) return;
        try (var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var path=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"),"evidence",role+"-visit-"+label+".png");
            image.writeToFile(path);
        } catch (Exception ex) {throw PokemonProbe.fail("Could not save visit view "+ex.getClass().getSimpleName());}
    }
}

package dev.mobsear.server;

import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;

/** Observe normal client packets, rendered frames and the game's OpenAL output; never supply effects. */
final class VisitFeedbackProof {
    private static final class Chew {
        final int number, id;
        final long started;
        float minPitch=90, maxPitch=-90, lastPitch, swingStart;
        int samples, bites, frames, direction, reversals;
        long removed=-1;
        boolean cry;
        Chew(int number,int id,long started) { this.number=number; this.id=id; this.started=started; }
    }
    private final List<Chew> chews=new ArrayList<>();
    private int entity=-1, hearts, cries, crumbs, noms, heartFrame;
    private Chew current;
    private boolean active;
    private long began;
    private String role;

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
            mc.execute(()->{
                if (!active || a.getEntityId()!=entity) return;
                cries++;
                if (current!=null && current.removed<0) throw PokemonProbe.fail("Species cry preceded berry removal");
                if (current!=null && !current.cry) {
                    current.cry=true;
                    record(mc,"cry",current,"species-animation="+a.getAnimation());
                } else record(mc,"pat-cry",null,"species-animation="+a.getAnimation());
            });
        if (msg instanceof ClientboundAddEntityPacket p && p.getType()==EntityType.ITEM_DISPLAY)
            mc.execute(()->{
                if (!active) return;
                if (current!=null && (current.removed<0 || !current.cry || current.frames!=4))
                    throw PokemonProbe.fail("A second berry appeared before the first completed its client sequence");
                current=new Chew(chews.size()+1,p.getId(),mc.level.getGameTime());
                chews.add(current);
                if (chews.size()>2) throw PokemonProbe.fail("An extra client berry prop appeared");
                record(mc,"prop-add",current,"input="+(current.number==1?"right-click":"toss"));
            });
        if (msg instanceof ClientboundRemoveEntitiesPacket p)
            mc.execute(()->{
                if (!active || current==null || current.removed>=0 || !p.getEntityIds().contains(current.id)) return;
                current.removed=mc.level.getGameTime();
                if (current.samples<12 || current.maxPitch-current.minPitch<=5 || current.reversals<2
                        || current.frames!=3 || current.bites<4)
                    throw PokemonProbe.fail("Incomplete rendered chew "+current.number+" samples="+current.samples+
                            " pitchRange="+(current.maxPitch-current.minPitch)+" reversals="+current.reversals+
                            " frames="+current.frames+" eatSounds="+current.bites);
                record(mc,"prop-remove",current,"pitchRange="+(current.maxPitch-current.minPitch)+" reversals="+current.reversals);
            });
        if (msg instanceof ClientboundSoundPacket p) {
            String sound=p.getSound().value().getLocation().toString();
            if (sound.equals("minecraft:entity.generic.eat")) mc.execute(()->{
                if (active && current!=null && current.removed<0) {
                    current.bites++;
                    record(mc,"eat",current,sound);
                }
            });
            if (sound.equals("mobsear:nom_nom")) mc.execute(()->{
                if (active) { noms++; throw PokemonProbe.fail("The spoken nom clip played during visit care"); }
            });
        }
        if (msg instanceof ClientboundLevelParticlesPacket p) {
            if (p.getParticle().getType()==ParticleTypes.HEART) mc.execute(()->{ if (active) hearts+=p.getCount(); });
            if (p.getParticle().getType()==ParticleTypes.ITEM) mc.execute(()->{ if (active) crumbs+=p.getCount(); });
        }
    }

    void stage(PokemonCase scenario,int id) {
        if (active && scenario!=PokemonCase.WALKING_PIKACHU) {
            if (hearts!=4 || cries!=3 || noms!=0 || chews.size()!=2 || crumbs<8
                    || chews.stream().anyMatch(c->c.removed<0 || !c.cry || c.frames!=4 || c.bites<4 || c.reversals<2))
                throw PokemonProbe.fail("Missing client visit effects hearts="+hearts+" cries="+cries+" nom="+noms+
                        " props="+chews.size()+" crumbs="+crumbs);
            record(Minecraft.getInstance(),"care-end",null,"complete=true");
            System.out.println("MOBSEAR_POKEMON_CLIENT_CARE actualHearts="+hearts+" speciesCry="+cries+
                    " nomSounds="+noms+" berryCrumbs="+crumbs+" props=2 completed=2 renderedSequences=2");
            active=false;
        }
        if (scenario==PokemonCase.WALKING_PIKACHU && !active) {
            entity=id; active=true; began=System.nanoTime();
            role=System.getenv("MOBSEAR_AV_ROLE");
            if (role==null || System.getenv("MOBSEAR_GAME_AUDIO")==null)
                throw PokemonProbe.fail("Missing hosted game-output evidence paths");
            record(Minecraft.getInstance(),"care-start",null,"entity="+entity);
        }
    }

    void frame(Minecraft mc,String observer) {
        if (!active || mc.level==null) return;
        if (!observer.equals(role)) throw PokemonProbe.fail("Mismatched audiovisual observer "+observer);
        if (current!=null) {
            var mob=mc.level.getEntity(entity);
            if (mob==null) throw PokemonProbe.fail("Missing rendered visitor during care");
            long age=mc.level.getGameTime()-current.started;
            if (current.removed<0) {
                var prop=mc.level.getEntity(current.id);
                if (prop instanceof Display.ItemDisplay display && !display.getSlot(0).get().isEmpty()) {
                    var food=display.getSlot(0).get();
                    var expected=current.number==1?CobblemonItems.ORAN_BERRY:CobblemonItems.SITRUS_BERRY;
                    var mouth=mob.getEyePosition().add(mob.getLookAngle().scale(Math.max(.25,mob.getBbWidth()*.55))).add(0,-.12,0);
                    double separation=display.position().distanceTo(mouth);
                    if (food.getCount()!=1 || !food.is(expected) || separation>.65)
                        throw PokemonProbe.fail("Wrong rendered mouth berry "+current.number+" stack="+food+" distance="+separation);
                    float pitch=mob.getXRot();
                    current.minPitch=Math.min(current.minPitch,pitch);
                    current.maxPitch=Math.max(current.maxPitch,pitch);
                    if (current.samples==0) current.swingStart=pitch;
                    float delta=pitch-current.lastPitch;
                    int direction=Math.abs(delta)>=.5F ? (delta>0?1:-1) : 0;
                    if (current.samples>0 && direction!=0 && direction!=current.direction) {
                        if (current.direction!=0 && Math.abs(current.lastPitch-current.swingStart)>5) {
                            current.reversals++;
                            if (current.reversals<=2) {
                                capture(mc,"chew-"+current.number+"-bob-"+current.reversals);
                                record(mc,"frame-bob-"+current.reversals,current,"pitch="+pitch);
                            }
                        }
                        current.swingStart=current.lastPitch;
                        current.direction=direction;
                    }
                    current.lastPitch=pitch;
                    current.samples++;
                    String phase=current.frames==0 && age<=10 ? "beginning"
                            : current.frames==1 && age>=16 ? "middle"
                            : current.frames==2 && age>=32 ? "end" : null;
                    if (phase!=null) {
                        capture(mc,"chew-"+current.number+"-"+phase);
                        current.frames++;
                        record(mc,"frame-"+phase,current,String.format(Locale.ROOT,"pitch=%.2f mouthDistance=%.3f",pitch,separation));
                    }
                } else if (age>4) throw PokemonProbe.fail("Berry display or item metadata was absent during chewing");
            } else if (current.frames==3 && mc.level.getGameTime()>current.removed) {
                if (mc.level.getEntity(current.id)!=null) throw PokemonProbe.fail("Berry remained rendered after removal");
                capture(mc,"chew-"+current.number+"-after");
                current.frames++;
                record(mc,"frame-after",current,"berryAbsent=true");
            }
        }
        if (hearts>0 && ++heartFrame==4) capture(mc,"head-pat");
    }

    private void capture(Minecraft mc,String label) {
        try (var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var path=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"),"evidence",role+"-visit-"+label+".png");
            Files.createDirectories(path.getParent());
            image.writeToFile(path);
        } catch (Exception ex) { throw PokemonProbe.fail("Could not save visit view "+ex.getClass().getSimpleName()); }
    }

    private void record(Minecraft mc,String event,Chew chew,String detail) {
        try {
            var path=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"),"evidence",role+"-visit-timeline.tsv");
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) Files.writeString(path,"event\tcycle\tgameTick\telapsedMs\taudioBytes\tpropId\tdetail\n");
            var audio=Path.of(System.getenv("MOBSEAR_GAME_AUDIO"));
            long bytes=Files.exists(audio)?Files.size(audio):0;
            String line=event+"\t"+(chew==null?0:chew.number)+"\t"+mc.level.getGameTime()+"\t"+
                    (System.nanoTime()-began)/1_000_000L+"\t"+bytes+"\t"+(chew==null?-1:chew.id)+"\t"+detail+"\n";
            Files.writeString(path,line,StandardOpenOption.APPEND);
        } catch (Exception ex) { throw PokemonProbe.fail("Could not save visit timeline "+ex.getClass().getSimpleName()); }
    }
}

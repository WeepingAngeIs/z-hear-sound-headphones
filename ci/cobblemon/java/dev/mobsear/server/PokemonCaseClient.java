package dev.mobsear.server;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.MobsEar;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.Packets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import dev.mobsear.client.NoticeFixture;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod(value="mobsear",dist=Dist.CLIENT)
public final class PokemonCaseClient {
    private Minecraft mc;
    private final boolean acoustic="1".equals(System.getenv("MOBSEAR_POKEMON_ACOUSTIC"));
    private boolean installed, hello, caller, solo, armed, acknowledged, sent, uttered, recognized, aligned;
    private int ticks, alignedTicks;
    private PokemonProbe.Message stage;
    private PokemonCase c;
    private String capture, waiting;
    private boolean saved, guardVerified, scopedGuardian;
    private boolean sourceKnown;
    private Packets.Heard confirmation, delayed;
    private long lastReplyId;
    private double sourceX,sourceZ;
    private int sourceTick, lastDeerTick, deerFrames;
    private double lastDeerX;
    private net.minecraft.world.phys.Vec3 trackedCaller;

    public PokemonCaseClient() {
        PokemonProbe.client=this::stage;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::renderDeer);
    }
    private void stage(PokemonProbe.Message request) {
        if (request.stage()==PokemonProbe.SOURCE) {
            if (stage==null || stage.stage()!=PokemonProbe.FIRE || request.entity()!=stage.entity() || request.scenario()!=stage.scenario() || !c.accepts())
                throw PokemonProbe.fail("Unmatched authenticated source observation");
            sourceX=request.sourceX(); sourceZ=request.sourceZ(); sourceKnown=true;
            sourceTick=ticks;
            return;
        }
        if (request.stage()==PokemonProbe.VERIFIED_GUARD) {
            if (stage==null || request.entity()!=stage.entity() || request.scenario()!=stage.scenario() || !c.guardPicture())
                throw PokemonProbe.fail("Unmatched protected-state acknowledgment");
            guardVerified=true;
            return;
        }
        stage=request;
        c=PokemonCase.values()[request.scenario()];
        acknowledged=sent=uttered=recognized=aligned=saved=guardVerified=scopedGuardian=false;
        ticks=alignedTicks=0; capture=null;
        sourceKnown=false; trackedCaller=null;
        deerFrames=0; lastDeerTick=-1; lastDeerX=Double.NaN;
        if (request.stage()==PokemonProbe.FIRE) {
            var speaker=caller ? mc.player : mc.level.players().stream().filter(p->p.getGameProfile().getName().equals("PokemonCaller")).findFirst().orElse(null);
            if (speaker!=null) trackedCaller=speaker.position();
        }
        if (request.stage()==PokemonProbe.BEFORE) {
            confirmation=null;
            NoticeFixture.clear();
            if (caller && !armed) {
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_N));
                armed=true;
            }
        }
        if (acoustic && request.stage()==PokemonProbe.BEFORE) {
            VoiceClient.setVoiceActivated(c==PokemonCase.EEVEE);
            KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V), c!=PokemonCase.EEVEE);
        }
    }
    private void tick(ClientTickEvent.Post ignored) {
        mc=Minecraft.getInstance();
        if (!installed) { FixtureSound.install(); installed=true; }
        mc.getToasts().clear();
        if (mc.player==null || mc.level==null) {
            if (mc.screen!=null) {
                waitFor(mc.screen.getClass().getSimpleName()+" / "+mc.screen.getTitle().getString());
                continueDisposableExperimentalWorld();
            }
            return;
        }
        if ((!hello || caller) && !VoiceClient.connected()) { waitFor("voice session"); return; }
        if (mc.screen instanceof PauseScreen pause) pause.onClose();
        if (!hello) {
            if (mc.screen!=null || !mc.isWindowActive()) {
                waitFor(mc.screen==null ? "window focus" : mc.screen.getClass().getSimpleName()); return;
            }
            var connection=mc.getConnection();
            if (!connection.hasChannel(Packets.SpeciesCall.TYPE)) throw PokemonProbe.fail("Missing negotiated species channel");
            if (!connection.hasChannel(Packets.EntityCall.TYPE)) throw PokemonProbe.fail("Missing negotiated mob name channel");
            if (!connection.hasChannel(Packets.Heard.TYPE)) throw PokemonProbe.fail("Missing negotiated confirmation channel");
            if (!connection.hasChannel(Packets.CallRequest.TYPE)) throw PokemonProbe.fail("Missing numbered call channel");
            for (var check:PokemonCase.requiredRegistryEntries())
                if (PokemonSpecies.getByIdentifier(check.species)==null) {
                    waitFor("synced species " + check.species); return;
                }
            solo=connection.getConnection().isMemoryConnection();
            caller=solo || mc.getUser().getName().equals("PokemonCaller");
            if (VoiceClient.options().recognitionDiagnostics()) throw PokemonProbe.fail("Diagnostics unexpectedly enabled by default");
            VoiceClient.options().setRecognitionDiagnostics(acoustic);
            var displayHeard=MobsEar.callHeard;
            MobsEar.callHeard=ack -> {
                if (!caller || c==null || expectedListener()==null || !expectedListener().equals(ack.id())
                        || ack.species()==c.ordinaryEntity() || confirmation!=null || ack.requestId()<=lastReplyId)
                    throw PokemonProbe.fail("Unexpected confirmed listener for "+c+" on "+mc.getUser().getName()+": "+ack);
                confirmation=ack;
                lastReplyId=ack.requestId();
                if (!acoustic && c==PokemonCase.NEAR_EDGE) {
                    // Keep this genuine reply in flight until a later out-of-range call is pending.
                    delayed=ack;
                } else if (!acoustic && (c==PokemonCase.LOOK || c==PokemonCase.APPROACH || c==PokemonCase.BLOCKED)) {
                    String transition;
                    if (c==PokemonCase.LOOK) {
                        NoticeFixture.cycleRecognition();
                        transition="recognition-off-on";
                    } else if (c==PokemonCase.APPROACH) {
                        VoiceClient.options().setMuted(true);
                        VoiceClient.options().setMuted(false);
                        transition="mute-unmute";
                    } else {
                        VoiceClient.options().setDeafened(true);
                        VoiceClient.options().setDeafened(false);
                        transition="deafen-undeafen";
                    }
                    rejectDelayed(ack,transition);
                } else displayHeard.accept(ack);
            };
            String side=solo ? "home-client" : caller ? "caller-client" : "viewer-client";
            PokemonRegistryAudit.check(side);
            dev.mobsear.client.EntityNamesAudit.check(side);
            mc.options.fov().set(caller ? 30 : 45);
            hello=true;
            PacketDistributor.sendToServer(new PokemonProbe.Message(PokemonProbe.HELLO,0,0));
            System.out.println("POKEMON_CLIENT_READY "+mc.getUser().getName()+" integrated="+solo+" registries=pikachu,acideon,hearfixture:chimekin");
        }
        if (stage==null || acknowledged) return;
        ticks++;
        if (ticks>900) throw PokemonProbe.fail("client timeout "+c+" stage="+stage.stage()+" caller="+caller+
                " screen="+(mc.screen==null?"none":mc.screen.getClass().getSimpleName())+" acoustic="+acoustic+
                " localRecognition="+recognized+" facing="+aligned+" sent="+sent+" serverConfirmed="+(confirmation!=null)+
                " sourceReceived="+sourceKnown+" target="+mc.level.getEntity(stage.entity()));
        if (stage.stage()==PokemonProbe.BEFORE) {
            if (ticks==100 || ticks==300) System.out.println("POKEMON_CLIENT_TRACK "+c+" id="+stage.entity()+" entity="+mc.level.getEntity(stage.entity())+
                    " camera="+(mc.getCameraEntity()==mc.player)+" callerAt="+mc.player.position()+" arenaLoaded="+mc.level.hasChunkAt(new net.minecraft.core.BlockPos(0,104,8)));
            if (ticks<4 || mc.level.getEntity(stage.entity())==null) return;
            if (c==PokemonCase.PACK_FORM) c.checkTrackedForm((PokemonEntity)mc.level.getEntity(stage.entity()),"Client before call");
            if (acoustic && c==PokemonCase.PIKACHU && !recognizerReady()) return;
            if ((solo || !caller) && c.beforePicture() && !saved) {
                if (ticks<35 || mc.screen!=null || mc.getOverlay()!=null) return;
                capture="before"; return;
            }
            reply(PokemonProbe.BEFORE); acknowledged=true;
            return;
        }
        if (caller && !sent) {
            if (acoustic) {
                if (c==PokemonCase.OWL_GUARDIAN && ticks>=180 && !scopedGuardian && uttered && FixtureSound.finishedPhrase()) {
                    scopedGuardian=true;
                    uttered=false;
                    KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V),true);
                }
                if (!uttered) {
                    try {
                        String clip=c==PokemonCase.OWL_GUARDIAN ? (scopedGuardian ? "owl-guardian-explicit" : "owl-guardian") : c.spoken().getPath();
                        FixtureSound.say(Files.readAllBytes(Path.of(System.getenv("MOBSEAR_POKEMON_PCM"),clip+".pcm")));
                    } catch (Exception ex) { throw PokemonProbe.fail("Missing stock synthetic PCM "+c); }
                    uttered=true;
                }
                if (FixtureSound.finishedPhrase()) KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V),false);
                Component label=c.ordinaryEntity() ? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(c.spoken()).getDescription()
                        : PokemonSpecies.getByIdentifier(c.spoken()).getTranslatedName();
                String expected=Component.translatable("mobsear.speech.recognized",label).getString();
                recognized |= expected.equals(NoticeFixture.localMatch());
                if (recognized) { reply(PokemonProbe.FIRE); sent=true; }
            } else {
                // Only the Linux recognizer boundary is replayed. The real server validates the client's normal PCM and species packet.
                byte[] pcm=new byte[640];
                for (int i=0;i<320;i++) { int v=(i%20-10)*650; pcm[i*2]=(byte)v; pcm[i*2+1]=(byte)(v>>8); }
                reply(PokemonProbe.ARRANGE);
                PacketDistributor.sendToServer(new Packets.Up(pcm));
                if (c==PokemonCase.BROAD_COW) NoticeFixture.send(new Packets.Call(dev.mobsear.net.Cue.MOBS));
                else if (c.ordinaryEntity()) NoticeFixture.send(new Packets.EntityCall(c.spoken()));
                else NoticeFixture.send(new Packets.SpeciesCall(c.spoken()));
                if (c==PokemonCase.FAR) {
                    if (delayed==null) throw PokemonProbe.fail("Missing genuine delayed near reply");
                    rejectDelayed(delayed,"near-after-new-far");
                    delayed=null;
                }
                reply(PokemonProbe.FIRE); sent=true;
            }
        }
        if (c.accepts() && mc.level.getEntity(stage.entity()) instanceof net.minecraft.world.entity.LivingEntity entity) {
            if (c.ordinaryEntity()) {
                var id=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                // The genuine Mimic server can present this exact tracked zombie as its synthetic player disguise.
                boolean disguise=c.nativeMimic() && entity instanceof net.minecraft.client.player.RemotePlayer;
                if (!disguise && !id.equals(c.species)) throw PokemonProbe.fail("Wrong tracked entity type "+id+" for "+c);
            } else if (!(entity instanceof PokemonEntity pokemon) || !pokemon.getPokemon().getSpecies().getResourceIdentifier().equals(c.species)) {
                throw PokemonProbe.fail("Client received wrong tracked Pokémon "+c);
            }
            double yaw=Math.toDegrees(Math.atan2(sourceZ-entity.getZ(), sourceX-entity.getX()))-90;
            double error=Math.abs(Mth.wrapDegrees(entity.getYHeadRot()-yaw));
            if (c.ordinaryEntity() && (ticks==2 || ticks==6 || ticks==12 || ticks==18 || ticks==30))
                System.out.println("ENTITY_CLIENT_FACING "+c+" tick="+ticks+" sourceKnown="+sourceKnown+" source="+sourceX+","+sourceZ+
                        " trackedCaller="+trackedCaller+" mob="+entity.position()+" head="+entity.getYHeadRot()+" error="+(sourceKnown?Math.round(error):"unknown"));
            if (c==PokemonCase.GLOBAL_PACK && (ticks==6 || ticks==18 || ticks==36))
                System.out.println("POKEMON_BOXTAIL_CLIENT tick="+ticks+" caller="+caller+" requestSent="+sent+
                        " sourceReceived="+sourceKnown+" heardRequest="+(confirmation==null ? 0 : confirmation.requestId())+
                        " head="+Math.round(entity.getYHeadRot())+" headError="+(sourceKnown ? Math.round(error) : "unknown"));
            if (c!=PokemonCase.WALKING_DEER && sourceKnown && error<=30 && ++alignedTicks>=2) aligned=true;
        }
        if ((caller && !sent) || (c.accepts() && !aligned) || ticks<5) return;
        if (c==PokemonCase.WALKING_DEER && !saved) return;
        if (caller && expectedListener()!=null && confirmation==null) return;
        if (c==PokemonCase.LOPUNNY && caller && !Component.translatable("mobsear.speech.heard",
                PokemonSpecies.getByIdentifier(c.species).getTranslatedName()).getString().equals(overlay()))
            throw PokemonProbe.fail("Missing confirmed Lopunny HUD notice: "+overlay());
        if (c==PokemonCase.ZOMBIE_CALL_MIMIC && caller && !Component.translatable("mobsear.speech.heard",
                Component.translatable("mobsear.speech.dungeon_mimic")).getString().equals(overlay()))
            throw PokemonProbe.fail("Exact zombie call showed the wrong listener: "+overlay());
        if (c==PokemonCase.LOPUNNY_FAR && caller) {
            if (ticks<45) return;
            if (confirmation!=null || overlay().contains("heard you")) throw PokemonProbe.fail("False distant Lopunny success");
        }
        if (c.guardPicture() && !guardVerified) return;
        if ((solo || !caller) && c.guardPicture() && !saved) { capture="protected"; return; }
        if (c==PokemonCase.PACK_FORM) {
            c.checkTrackedForm((PokemonEntity)mc.level.getEntity(stage.entity()),"Client after call");
            if (!saved && capture==null) System.out.println("POKEMON_PACK_FORM_CLIENT id="+c.species+
                    " form=Pom-Pom aspect=pom-pom-style aligned="+aligned+" caller="+caller);
        }
        if ((solo || !caller) && c.accepts() && !saved) { capture="after"; return; }
        reply(PokemonProbe.SEEN); acknowledged=true;
        if (acoustic) System.out.println("POKEMON_REAL_SPEECH "+c+" canonical=true client-facing="+aligned+" confirmed="+(confirmation!=null)+
                " mode="+(c==PokemonCase.EEVEE?"voice-activation":"push-to-talk")+
                (c==PokemonCase.OWL_GUARDIAN ? " syntheticVariant="+(scopedGuardian?"explicit-call":"bare-name") : ""));
    }

    private ResourceLocation expectedListener() {
        if (c.nativeMimic()) return dev.mobsear.net.MobCallTypes.DUNGEON_MIMIC;
        // In these two trials a second eligible mob actually hears the call.
        if (c==PokemonCase.BROAD_COW) return ResourceLocation.withDefaultNamespace("zombie");
        if (c==PokemonCase.COOLDOWN_HELD) return c.species;
        return c.accepts() ? c.spoken() : null;
    }
    private void waitFor(String reason) {
        if (!reason.equals(waiting)) System.out.println("POKEMON_CLIENT_WAIT " + reason);
        waiting=reason;
    }
    private void continueDisposableExperimentalWorld() {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))) return;
        var root=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT")).resolve("solo").toAbsolutePath().normalize();
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(root)) return;
        String title=mc.screen.getTitle().getString();
        if (!title.equals(Component.translatable("selectWorld.backupQuestion.experimental").getString())
                && !title.equals(Component.translatable("selectWorld.experimental.title").getString())
                && !title.equals(Component.translatable("selectWorld.warning.experimental.title").getString())) return;
        var allowed=java.util.Set.of(Component.translatable("selectWorld.backupJoinSkipButton").getString(),
                Component.translatable("gui.proceed").getString(),Component.translatable("gui.yes").getString());
        for (var widget:mc.screen.children()) {
            if (widget instanceof net.minecraft.client.gui.components.Button button && button.active && allowed.contains(button.getMessage().getString())) {
                System.out.println("POKEMON_FIXTURE_CONTINUE public datapacks in disposable solo save");
                button.onPress(); return;
            }
        }
    }
    private boolean recognizerReady() {
        // Inspect only the worker status; the acoustic run never writes the recognition result mailbox.
        try {
            var inst=VoiceClient.class.getDeclaredField("instance"); inst.setAccessible(true);
            var session=VoiceClient.class.getDeclaredField("session"); session.setAccessible(true);
            Object s=session.get(inst.get(null));
            var speech=s.getClass().getDeclaredField("speech"); speech.setAccessible(true);
            Object worker=speech.get(s);
            var status=worker.getClass().getDeclaredMethod("status"); status.setAccessible(true);
            return status.invoke(worker).toString().equals("READY");
        } catch (ReflectiveOperationException ex) { throw PokemonProbe.fail("Cannot read speech readiness "+ex); }
    }
    private String overlay() {
        return NoticeFixture.text();
    }

    private void rejectDelayed(Packets.Heard ack,String transition) {
        VoiceClient.heard(ack);
        if (overlay().contains("heard you")) throw PokemonProbe.fail("Stale success after "+transition);
        System.out.println("MOBSEAR_CALL_REPLY_GUARD "+transition+" stale-success=false");
    }
    private void reply(int code) {
        PacketDistributor.sendToServer(new PokemonProbe.Message(code,stage.scenario(),stage.entity()));
    }

    // Inspect the interpolated angles of the actual Twilight Forest deer when its renderer drew it.
    private void renderDeer(RenderLivingEvent.Post<?,?> event) {
        if (c!=PokemonCase.WALKING_DEER || !sourceKnown || aligned || ticks-sourceTick>26
                || !(event.getEntity() instanceof Mob deer) || deer.getId()!=stage.entity() || lastDeerTick==deer.tickCount) return;
        lastDeerTick=deer.tickCount;
        float part=event.getPartialTick();
        double x=Mth.lerp(part,deer.xo,deer.getX()), z=Mth.lerp(part,deer.zo,deer.getZ());
        double bearing=Math.toDegrees(Math.atan2(sourceZ-z,sourceX-x))-90;
        float head=Mth.rotLerp(part,deer.yHeadRotO,deer.getYHeadRot());
        float body=Mth.rotLerp(part,deer.yBodyRotO,deer.yBodyRot);
        double error=Math.abs(Mth.wrapDegrees(head-bearing)), neck=Math.abs(Mth.wrapDegrees(head-body));
        boolean movingAway=lastDeerX-x>.015;
        lastDeerX=x;
        deerFrames=movingAway && error<=30 && neck<=deer.getMaxHeadYRot()+1 ? deerFrames+1 : 0;
        if (deerFrames>=2) {
            aligned=true;
            capture=caller?"caller-view":"viewer-view";
            System.out.printf(java.util.Locale.ROOT,
                    "ENTITY_DEER_RENDER role=%s headError=%.1f neck=%.1f movingAway=true frames=%d%n",
                    caller?"caller":"viewer",error,neck,deerFrames);
        }
    }

    private void render(RenderFrameEvent.Post ignored) {
        if (capture==null) return;
        Path output=Path.of(System.getenv("MOBSEAR_POKEMON_ROOT"),"evidence");
        String name=(solo?"solo":"multi")+"-"+c.name().toLowerCase(java.util.Locale.ROOT)+"-"+capture+".png";
        try (var pic=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            if ("before".equals(capture)) {
                // Entity tracking can precede the first actual terrain draw after teleport.
                int floor=0;
                for (int i=2;i<=6;i++) {
                    int pixel=pic.getPixelRGBA(pic.getWidth()*i/8,pic.getHeight()*2/3);
                    int red=pixel&255, green=(pixel>>>8)&255, blue=(pixel>>>16)&255;
                    if (red>=35 && red<=180 && Math.abs(green-red)<12 && Math.abs(blue-red)<12) floor++;
                }
                if (floor<3) return;
            }
            Files.createDirectories(output); pic.writeToFile(output.resolve(name));
        } catch (Exception ex) { throw PokemonProbe.fail("Cannot save rendered Pokémon "+name+": "+ex); }
        capture=null; saved=true;
    }
}

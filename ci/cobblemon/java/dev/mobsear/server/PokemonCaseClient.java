package dev.mobsear.server;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.Packets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
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
    private double sourceX,sourceZ;
    private net.minecraft.world.phys.Vec3 trackedCaller;

    public PokemonCaseClient() {
        PokemonProbe.client=this::stage;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
    }
    private void stage(PokemonProbe.Message request) {
        if (request.stage()==PokemonProbe.SOURCE) {
            if (stage==null || stage.stage()!=PokemonProbe.FIRE || request.entity()!=stage.entity() || request.scenario()!=stage.scenario() || !c.accepts())
                throw PokemonProbe.fail("Unmatched authenticated source observation");
            sourceX=request.sourceX(); sourceZ=request.sourceZ(); sourceKnown=true;
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
        if (request.stage()==PokemonProbe.FIRE) {
            var speaker=caller ? mc.player : mc.level.players().stream().filter(p->p.getGameProfile().getName().equals("PokemonCaller")).findFirst().orElse(null);
            if (speaker!=null) trackedCaller=speaker.position();
        }
        if (acoustic && request.stage()==PokemonProbe.BEFORE) {
            if (!armed) {
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_N));
                armed=true;
            }
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
            for (var check:PokemonCase.requiredRegistryEntries())
                if (PokemonSpecies.getByIdentifier(check.species)==null) {
                    waitFor("synced species " + check.species); return;
                }
            solo=connection.getConnection().isMemoryConnection();
            caller=solo || mc.getUser().getName().equals("PokemonCaller");
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
                " heard="+recognized+" facing="+aligned+" signed="+sent+" target="+mc.level.getEntity(stage.entity()));
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
                recognized |= expected.equals(overlay());
                if (recognized) { reply(PokemonProbe.FIRE); sent=true; }
            } else {
                // Only the Linux recognizer boundary is replayed. The real server validates the client's normal PCM and species packet.
                byte[] pcm=new byte[640];
                for (int i=0;i<320;i++) { int v=(i%20-10)*650; pcm[i*2]=(byte)v; pcm[i*2+1]=(byte)(v>>8); }
                reply(PokemonProbe.ARRANGE);
                PacketDistributor.sendToServer(new Packets.Up(pcm));
                if (c==PokemonCase.BROAD_COW) PacketDistributor.sendToServer(new Packets.Call(dev.mobsear.net.Cue.MOBS));
                else if (c.ordinaryEntity()) PacketDistributor.sendToServer(new Packets.EntityCall(c.spoken()));
                else PacketDistributor.sendToServer(new Packets.SpeciesCall(c.spoken()));
                reply(PokemonProbe.FIRE); sent=true;
            }
        }
        if (c.accepts() && mc.level.getEntity(stage.entity()) instanceof net.minecraft.world.entity.LivingEntity entity) {
            if (c.ordinaryEntity()) {
                var id=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                // The genuine Mimic server can present this exact tracked zombie as its synthetic player disguise.
                boolean disguise=c==PokemonCase.DUNGEON_MIMIC && entity instanceof net.minecraft.client.player.RemotePlayer;
                if (!disguise && !id.equals(c.species)) throw PokemonProbe.fail("Wrong tracked entity type "+id+" for "+c);
            } else if (!(entity instanceof PokemonEntity pokemon) || !pokemon.getPokemon().getSpecies().getResourceIdentifier().equals(c.species)) {
                throw PokemonProbe.fail("Client received wrong tracked Pokémon "+c);
            }
            double yaw=Math.toDegrees(Math.atan2(sourceZ-entity.getZ(), sourceX-entity.getX()))-90;
            double error=Math.abs(Mth.wrapDegrees(entity.getYHeadRot()-yaw));
            if (c.ordinaryEntity() && (ticks==2 || ticks==6 || ticks==12 || ticks==18 || ticks==30))
                System.out.println("ENTITY_CLIENT_FACING "+c+" tick="+ticks+" sourceKnown="+sourceKnown+" source="+sourceX+","+sourceZ+
                        " trackedCaller="+trackedCaller+" mob="+entity.position()+" head="+entity.getYHeadRot()+" error="+(sourceKnown?Math.round(error):"unknown"));
            if (sourceKnown && error<=30 && ++alignedTicks>=2) aligned=true;
        }
        if ((caller && !sent) || (c.accepts() && !aligned) || ticks<5) return;
        if (c.guardPicture() && !guardVerified) return;
        if ((solo || !caller) && c.guardPicture() && !saved) { capture="protected"; return; }
        if (c==PokemonCase.PACK_FORM) {
            c.checkTrackedForm((PokemonEntity)mc.level.getEntity(stage.entity()),"Client after call");
            if (!saved && capture==null) System.out.println("POKEMON_PACK_FORM_CLIENT id="+c.species+
                    " form=Pom-Pom aspect=pom-pom-style aligned="+aligned+" caller="+caller);
        }
        if ((solo || !caller) && c.accepts() && !saved) { capture="after"; return; }
        reply(PokemonProbe.SEEN); acknowledged=true;
        if (acoustic) System.out.println("POKEMON_REAL_SPEECH "+c+" canonical=true client-facing="+aligned+
                " mode="+(c==PokemonCase.EEVEE?"voice-activation":"push-to-talk")+
                (c==PokemonCase.OWL_GUARDIAN ? " syntheticVariant="+(scopedGuardian?"explicit-call":"bare-name") : ""));
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
        try {
            var f=Gui.class.getDeclaredField("overlayMessageString"); f.setAccessible(true);
            var value=(Component)f.get(mc.gui); return value==null?"":value.getString();
        } catch (ReflectiveOperationException ex) { throw PokemonProbe.fail("Cannot read canonical feedback "+ex); }
    }
    private void reply(int code) {
        PacketDistributor.sendToServer(new PokemonProbe.Message(code,stage.scenario(),stage.entity()));
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

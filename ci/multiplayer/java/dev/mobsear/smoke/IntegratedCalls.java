package dev.mobsear.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.NoticeFixture;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.Cue;
import dev.mobsear.net.MicState;
import dev.mobsear.net.Packets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.lwjgl.glfw.GLFW;

/** Cross the actual shared-memory connection from game client to integrated server.
 * Only the platform-specific recognizer result is replayed from the Windows speech job. */
final class IntegratedCalls {
    private record Case(String label, int decision, boolean enderman, boolean automatic, int distance) {}
    private static final List<Case> CASES = List.of(
            new Case("Enderman",0,true,true,24), new Case("Spider",1,false,false,24),
            new Case("mob-Enderman",2,true,false,24), new Case("mob-Spider",2,false,true,24),
            new Case("mobs-Enderman",3,true,true,24), new Case("outside",2,false,true,33));
    private static final int X = 8, Y = 104, Z = 8;
    private static final List<String> EXPECTED = List.of("ENDERMAN","SPIDER","MOBS","MOBS");
    private final boolean acoustic = System.getenv("MOBSEAR_SYNTH_DEVICE") != null;
    private final NoticeProof notices = "1".equals(System.getenv("MOBSEAR_NOTICE_PROOF")) ? new NoticeProof() : null;
    private final List<String> decisions;
    // Each counter has a single writer. World entities stay on their owning thread.
    private volatile int stage = -1, ready = -1, released = -1, feedback = -1, seen = -1, entityId;
    private int age, aligned, clientStage = -1, clientTicks, seenTicks;
    private boolean claimed, installed, playedPhrase;
    private Mob subject;

    IntegratedCalls() {
        try { decisions = acoustic ? EXPECTED : Files.readAllLines(Path.of(System.getenv("MOBSEAR_CUE_PROOF"))); }
        catch (Exception error) { throw SmokeControl.failure("Cannot read Windows fixture decisions"); }
        if (!decisions.equals(EXPECTED))
            throw SmokeControl.failure("Unexpected Windows fixture decisions");
        NeoForge.EVENT_BUS.addListener(this::server);
        NeoForge.EVENT_BUS.addListener(this::client);
    }

    private Case scenario(int index) {
        return index < CASES.size() ? CASES.get(index) : new Case("Enderman-notice-" + (index - CASES.size()), 0, true, false, 18);
    }

    private int total() { return CASES.size() + (notices == null ? 0 : NoticeProof.COUNT); }

    private void prepare(ServerPlayer owner, int index) {
        var level = owner.serverLevel();
        var c = scenario(index);
        if (index == 0) {
            level.getServer().setDifficulty(Difficulty.NORMAL,true);
            level.setWeatherParameters(60000,0,false,false);
            level.setDayTime(1000);
            if (notices != null) {
                // Equipping the test chestplate can post a native chat announcement over the HUD.
                // Isolate the disposable framebuffer measurement without altering the game's chat renderer.
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_ANNOUNCE_ADVANCEMENTS)
                        .set(false, level.getServer());
            }
            for (int x=-5;x<=38;x++) for (int z=-5;z<=5;z++)
                level.setBlockAndUpdate(new BlockPos(X+x,Y-1,Z+z),Blocks.STONE.defaultBlockState());
            owner.setGameMode(GameType.SURVIVAL);
        }
        boolean picture = index >= CASES.size();
        if (picture) NoticeProof.equip(owner, index - CASES.size());
        // Keep the mob in view for HUD pictures. Looking down avoids a vanilla eye-contact trigger.
        owner.teleportTo(level,X+.5+c.distance,Y,Z+.5,picture ? 90 : -90,picture ? 13 : 0);
        subject = (c.enderman ? EntityType.ENDERMAN : EntityType.SPIDER).create(level);
        if (subject == null || !level.isDay()) throw SmokeControl.failure("Expected a daytime world");
        // Keep the outside control beyond the boundary for the whole synthetic utterance.
        // A normal spider can wander a block between starting the clip and receiving the cue.
        if (c.distance > 32) subject.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        pose();
        subject.setPersistenceRequired();
        if (!level.addFreshEntity(subject)) throw SmokeControl.failure("Failed to add single-player subject");
        entityId = subject.getId();
        age = aligned = 0;
        claimed = false;
        stage = index;
    }

    private void pose() {
        subject.moveTo(X+.5,Y,Z+.5,90,0);
        subject.yBodyRot = 90;
        subject.setYHeadRot(90);
    }
    private void server(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (stage == total() || server.getPlayerCount() == 0) return;
        if (server.isDedicatedServer() || server.getPlayerCount() != 1) throw SmokeControl.failure("Expected private single-player");
        var owner = server.getPlayerList().getPlayers().getFirst();
        if (stage < 0) { prepare(owner,0); return; }
        var c = scenario(stage);
        if (++age > 620) throw SmokeControl.failure("Integrated "+c.label+" stalled ready="+ready
                +" release="+released+" feedback="+feedback+" clientSeen="+seen+" claimed="+claimed+" aligned="+aligned);
        if (subject.getTarget()!=null || subject instanceof EnderMan e && (e.isCreepy() || e.hasBeenStaredAt() || e.isAngry()))
            throw SmokeControl.failure("Unexpected aggression in single-player: "+c.label);
        if (released < stage) {
            pose();
            if (age >= 35 && ready == stage) {
                if (Math.abs(subject.distanceTo(owner)-c.distance) > .05) throw SmokeControl.failure("Incorrect caller distance");
                released = stage;
            }
            return;
        }
        claimed |= subject.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.isRunning()
                && g.getGoal().getClass().getName().equals("dev.mobsear.server.MobReactions$ListenGoal"));
        if (Math.abs(Mth.wrapDegrees(subject.getYHeadRot()+90)) <= 25) aligned++;
        boolean inside = c.distance <= 32;
        if (!inside && subject.distanceToSqr(owner) <= 32 * 32) throw SmokeControl.failure("Outside subject crossed hearing boundary");
        if (!inside && claimed) throw SmokeControl.failure("Out-of-range integrated cue acted at " + subject.distanceTo(owner));
        if (feedback!=stage || (inside ? !claimed || aligned<6 || seen!=stage : age<80)) return;
        System.out.println("MOBSEAR_INTEGRATED_CASE "+c.label+" distance="+c.distance+" automatic="+c.automatic
                +" memory-connection=true daytime=true server-listen-goal="+claimed+" alignedTicks="+aligned
                +" live-windows-decoder="+acoustic+" target=none");
        subject.discard();
        if (stage+1 < total()) prepare(owner,stage+1);
        else { stage=total(); System.out.println("MOBSEAR_INTEGRATED_PASS"); }
    }

    private void client(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if (!installed) { FixtureSound.install(); installed=true; }
        int index=stage;
        if (index<0 || index>=total() || mc.getSingleplayerServer()==null || mc.level==null ||
                mc.player==null || mc.screen!=null || !mc.isWindowActive()) return;
        var link=mc.getConnection();
        if (link==null || !link.getConnection().isMemoryConnection() || !link.hasChannel(Packets.Call.TYPE)
                || !VoiceClient.connected()) throw SmokeControl.failure("Missing integrated mod connection");
        var c=scenario(index);
        int view=index-CASES.size();
        var v=InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V);
        if (clientStage!=index) {
            clientStage=index;
            clientTicks=seenTicks=0;
            playedPhrase=false;
            NoticeFixture.clear();
            // The out-of-range control must still prove the synthetic phrase was recognized locally.
            VoiceClient.options().setRecognitionDiagnostics(c.distance>32);
            if (view>=0) NoticeProof.prepare(mc,view);
            if (index==0) {
                if (!VoiceClient.options().localMobCalls() || !VoiceClient.voiceActivated()
                        || VoiceClient.options().muted() || VoiceClient.options().deafened())
                    throw SmokeControl.failure("Fresh single-player voice and Local Mob Calls defaults");
                VoiceClient.options().setNoiseSuppression(true);
            }
            if (index!=0) VoiceClient.setVoiceActivated(c.automatic);
            KeyMapping.set(v,!c.automatic);
            FixtureSound.tone=!acoustic;
        }
        var entity=mc.level.getEntity(entityId);
        if (++clientTicks<6 || !(entity instanceof Mob mob) || (view>=0 && !NoticeProof.ready(mc,view))) return;
        boolean inputReady = acoustic ? (index > 0 || NoticeFixture.recognizerReady()) : VoiceClient.localState()==MicState.SPEAKING;
        if (ready<index && inputReady && Math.abs(Mth.wrapDegrees(mob.getYHeadRot()+90))>=90)
            ready=index;
        if (released!=index) return;
        Cue cue=Cue.valueOf(decisions.get(c.decision));
        if (!playedPhrase) {
            playedPhrase=true;
            if (acoustic) {
                try { FixtureSound.say(Files.readAllBytes(Path.of(System.getenv("MOBSEAR_SYNTH_DEVICE"), c.decision+".pcm"))); }
                catch (Exception e) { throw SmokeControl.failure("Missing generated acoustic fixture"); }
            } else {
                if (view==0) mc.gui.setOverlayMessage(Component.literal("Quest updated"),false);
                KeyMapping.set(v,false);
                FixtureSound.tone=false;
                GazeClient.supplyDecision(cue);
            }
            return;
        }
        if (acoustic && FixtureSound.finishedPhrase()) KeyMapping.set(v,false);
        var receiver=Component.translatable("entity.minecraft."+(c.enderman?"enderman":"spider"));
        String success=Component.translatable("mobsear.speech.heard",receiver).getString();
        if (c.distance <= 32) {
            if (success.equals(NoticeFixture.text()) && (view<0 || notices.captured(view))) feedback=index;
        } else {
            var local=Component.translatable("mobsear.speech.recognized",Component.translatable("mobsear.speech.hostiles"));
            if (success.equals(NoticeFixture.text())) throw SmokeControl.failure("Out-of-range mob acknowledged hearing");
            if (local.getString().equals(NoticeFixture.localMatch())) feedback=index;
        }
        if (Math.abs(Mth.wrapDegrees(mob.getYHeadRot()+90))<=25) {
            if (++seenTicks>=3) seen=index;
        } else seenTicks=0;
    }
}

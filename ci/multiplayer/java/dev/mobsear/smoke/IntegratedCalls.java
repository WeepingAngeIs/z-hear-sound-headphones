package dev.mobsear.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.client.FixtureSound;
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
    private final List<String> decisions;
    // Each counter has a single writer. World entities stay on their owning thread.
    private volatile int stage = -1, ready = -1, released = -1, feedback = -1, seen = -1, entityId;
    private int age, aligned, clientStage = -1, clientTicks, seenTicks;
    private boolean claimed, installed;
    private Mob subject;

    IntegratedCalls() {
        try { decisions = Files.readAllLines(Path.of(System.getenv("MOBSEAR_CUE_PROOF"))); }
        catch (Exception error) { throw SmokeControl.failure("Cannot read Windows fixture decisions"); }
        if (!decisions.equals(List.of("ENDERMAN","SPIDER","MOBS","MOBS")))
            throw SmokeControl.failure("Unexpected Windows fixture decisions");
        NeoForge.EVENT_BUS.addListener(this::server);
        NeoForge.EVENT_BUS.addListener(this::client);
    }

    private void prepare(ServerPlayer owner, int index) {
        var level = owner.serverLevel();
        var c = CASES.get(index);
        if (index == 0) {
            level.getServer().setDifficulty(Difficulty.NORMAL,true);
            level.setWeatherParameters(60000,0,false,false);
            level.setDayTime(1000);
            for (int x=-5;x<=38;x++) for (int z=-5;z<=5;z++)
                level.setBlockAndUpdate(new BlockPos(X+x,Y-1,Z+z),Blocks.STONE.defaultBlockState());
            owner.setGameMode(GameType.SURVIVAL);
        }
        owner.teleportTo(level,X+.5+c.distance,Y,Z+.5,-90,0);
        subject = (c.enderman ? EntityType.ENDERMAN : EntityType.SPIDER).create(level);
        if (subject == null || !level.isDay()) throw SmokeControl.failure("Expected a daytime world");
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
        if (stage == CASES.size() || server.getPlayerCount() == 0) return;
        if (server.isDedicatedServer() || server.getPlayerCount() != 1) throw SmokeControl.failure("Expected private single-player");
        var owner = server.getPlayerList().getPlayers().getFirst();
        if (stage < 0) { prepare(owner,0); return; }
        var c = CASES.get(stage);
        if (++age > 260) throw SmokeControl.failure("Integrated "+c.label+" stalled ready="+ready
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
        if (!inside && claimed) throw SmokeControl.failure("Out-of-range integrated cue acted");
        if (feedback!=stage || (inside ? !claimed || aligned<6 || seen!=stage : age<80)) return;
        System.out.println("MOBSEAR_INTEGRATED_CASE "+c.label+" distance="+c.distance+" automatic="+c.automatic
                +" memory-connection=true daytime=true server-listen-goal="+claimed+" alignedTicks="+aligned+" target=none");
        subject.discard();
        if (stage+1 < CASES.size()) prepare(owner,stage+1);
        else { stage=CASES.size(); System.out.println("MOBSEAR_INTEGRATED_PASS"); }
    }

    private void client(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if (!installed) { FixtureSound.install(); installed=true; }
        int index=stage;
        if (index<0 || index>=CASES.size() || mc.getSingleplayerServer()==null || mc.level==null ||
                mc.player==null || mc.screen!=null || !mc.isWindowActive()) return;
        var link=mc.getConnection();
        if (link==null || !link.getConnection().isMemoryConnection() || !link.hasChannel(Packets.Call.TYPE)
                || !VoiceClient.connected()) throw SmokeControl.failure("Missing integrated mod connection");
        var c=CASES.get(index);
        var v=InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V);
        if (clientStage!=index) {
            clientStage=index;
            clientTicks=seenTicks=0;
            if (index==0) {
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_N));
                VoiceClient.options().setNoiseSuppression(true);
            }
            VoiceClient.setVoiceActivated(c.automatic);
            KeyMapping.set(v,!c.automatic);
            FixtureSound.tone=true;
        }
        var entity=mc.level.getEntity(entityId);
        if (++clientTicks<6 || !(entity instanceof Mob mob)) return;
        if (ready<index && VoiceClient.localState()==MicState.SPEAKING && Math.abs(Mth.wrapDegrees(mob.getYHeadRot()+90))>=90)
            ready=index;
        if (released!=index) return;
        Cue cue=Cue.valueOf(decisions.get(c.decision));
        if (FixtureSound.tone) {
            KeyMapping.set(v,false);
            FixtureSound.tone=false;
            GazeClient.supplyDecision(cue);
            return;
        }
        var name=cue==Cue.MOBS ? Component.translatable("mobsear.speech.hostiles") : Component.translatable("entity.minecraft."+cue.entityPath);
        if (Component.translatable("mobsear.speech.recognized",name).getString().equals(GazeClient.overlay(mc.gui))) feedback=index;
        if (Math.abs(Mth.wrapDegrees(mob.getYHeadRot()+90))<=25) {
            if (++seenTicks>=3) seen=index;
        } else seenTicks=0;
    }
}

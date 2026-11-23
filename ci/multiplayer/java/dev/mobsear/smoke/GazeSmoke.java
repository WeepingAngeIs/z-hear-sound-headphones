package dev.mobsear.smoke;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Observe a genuine client's tracked head rotation following a normal serverbound Call. */
final class GazeSmoke {
    static final int BEFORE = -100, SEND = -110;
    static final String[] CASES = {"enderman", "spider", "mob", "mobs", "mob-spider", "mobs-enderman"};
    static final int[] DECISIONS = {0, 1, 2, 3, 2, 3};
    static final int X = 0, Y = 108, Z = 200;
    static boolean handles(int value) {
        return value <= BEFORE && value > BEFORE - CASES.length || value <= SEND && value > SEND - CASES.length;
    }
    static boolean enderman(int index) { return index == 0 || index == 2 || index == 5; }
    private static final Set<UUID> ready = new HashSet<>();
    private static ServerPlayer speaker, observer;
    private static Mob mob;
    private static int index, stage, startTick, alignedTicks, firstAligned;
    private static boolean finished, accepted;

    static void start(ServerPlayer a, ServerPlayer b) {
        speaker = a;
        observer = b;
        var level = a.serverLevel();
        level.setDayTime(1000);
        for (int x = -8; x <= 28; x++) for (int z = -6; z <= 6; z++)
            level.setBlockAndUpdate(new BlockPos(X+x, Y-1, Z+z), Blocks.STONE.defaultBlockState());
        a.setGameMode(GameType.SURVIVAL);
        b.setGameMode(GameType.SPECTATOR);
        a.teleportTo(level, X+24.5, Y, Z+.5, -90, 0); // Looks away from the mob.
        NeoForge.EVENT_BUS.addListener(GazeSmoke::tick);
        begin();
    }

    private static void begin() {
        var level = speaker.serverLevel();
        observer.teleportTo(level, X+6.5, Y+.2, Z+2.5, 108.4f, enderman(index) ? 0 : 12);
        mob = (enderman(index) ? EntityType.ENDERMAN : EntityType.SPIDER).create(level);
        if (mob == null) throw SmokeControl.failure("cannot create gaze fixture");
        mob.moveTo(X+.5, Y, Z+.5, 90, 0);
        mob.yBodyRot = 90;
        mob.setYHeadRot(90);
        mob.setPersistenceRequired();
        if (!level.addFreshEntity(mob)) throw SmokeControl.failure("cannot place gaze fixture");
        alignedTicks = 0;
        accepted = false;
        firstAligned = -1;
        announce(BEFORE);
    }

    private static void announce(int next) {
        ready.clear();
        stage = next;
        startTick = speaker.serverLevel().getServer().getTickCount();
        for (var player : java.util.List.of(speaker, observer)) {
            PacketDistributor.sendToPlayer(player, new SmokeControl.Barrier(stage-index));
        }
    }
    static void acknowledge(ServerPlayer player, int value) {
        if ((player != speaker && player != observer) || value != stage-index || !ready.add(player.getUUID()))
            throw SmokeControl.failure("unexpected gaze acknowledgment " + value);
    }
    private static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        int age = event.getServer().getTickCount() - startTick;
        if (age > 160) throw SmokeControl.failure("gaze case " + CASES[index] + " stage " + stage
                + " ready=" + ready.size() + " aligned=" + alignedTicks + " head=" + mob.getYHeadRot());
        if (mob.getTarget() != null || mob instanceof EnderMan e && (e.isCreepy() || e.hasBeenStaredAt()))
            throw SmokeControl.failure("gaze fixture acquired an unintended target or stare");
        if (stage == BEFORE) {
            // Establish an unambiguous starting pose only while waiting for both clients' before frames.
            mob.moveTo(X+.5, Y, Z+.5, 90, 0);
            mob.yBodyRot = 90;
            mob.setYHeadRot(90);
            if (age >= 32 && ready.size() == 2) {
                System.out.printf(java.util.Locale.ROOT, "MOBSEAR_GAZE_SEND %s actual-distance=%.2f initial-head=%.1f%n",
                        CASES[index], mob.distanceTo(speaker), mob.getYHeadRot());
                announce(SEND);
            }
        } else {
            accepted |= mob.goalSelector.getAvailableGoals().stream().anyMatch(goal -> goal.isRunning()
                    && goal.getGoal().getClass().getName().equals("dev.mobsear.server.MobReactions$ListenGoal"));
            double expected = Math.toDegrees(Math.atan2(speaker.getZ()-mob.getZ(), speaker.getX()-mob.getX()))-90;
            if (Math.abs(Mth.wrapDegrees(mob.getYHeadRot() - expected)) <= 25) {
                alignedTicks++;
                if (firstAligned < 0) firstAligned = age;
            }
            if (ready.size() == 2 && accepted && alignedTicks >= 6) {
                System.out.println("MOBSEAR_GAZE_CLIENT_PASS " + CASES[index] + " firstAligned=" + firstAligned
                        + " alignedTicks=" + alignedTicks + " server-listen-goal=true target=none actual-game-clients=2");
                mob.discard();
                if (++index < CASES.length) begin();
                else {
                    finished = true;
                    DistractionSmoke.start(speaker,observer);
                }
            }
        }
    }
}

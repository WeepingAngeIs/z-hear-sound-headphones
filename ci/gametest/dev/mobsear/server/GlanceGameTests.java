package dev.mobsear.server;

import com.mojang.authlib.GameProfile;
import dev.mobsear.net.Cue;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Measures the entity angles used to render the head after multiple normal server AI ticks. */
@GameTestHolder("mobsear")
@PrefixGameTestTemplate(false)
public final class GlanceGameTests {
    @GameTest(template = "hearing_floor", batch = "walking_enderman")
    public static void walkingEndermanGlancesAndResumes(GameTestHelper test) {
        walking(test, EntityType.ENDERMAN, Cue.ENDERMAN);
    }

    @GameTest(template = "hearing_floor", batch = "walking_spider")
    public static void walkingSpiderGlancesAndResumes(GameTestHelper test) {
        walking(test, EntityType.SPIDER, Cue.SPIDER);
    }

    private static void walking(GameTestHelper test, EntityType<? extends Mob> type, Cue named) {
        var mob = test.spawn(type, 3, 2, 5);
        var origin = mob.position();
        var walk = new WalkAndLook(mob, test.absoluteVec(new Vec3(11.5, 2, 5.5)));
        // Spider registers a random stroll at this same priority. Give the fixture exclusive
        // ownership of its walking slot so recovery measures the known competing goal.
        mob.goalSelector.removeAllGoals(goal -> goal instanceof WaterAvoidingRandomStrollGoal);
        mob.goalSelector.addGoal(5, walk);
        var player = speaker(test, origin);
        for (int attempt = 0; attempt < 2; attempt++) {
            int begins = 15 + 44 * attempt;
            Cue cue = attempt == 0 ? named : Cue.MOBS;
            test.runAfterDelay(begins, () -> {
                test.assertTrue(walk.running && mob.distanceToSqr(origin) > 0.04, "The fixture never walked: running="
                        + walk.running + " grounded=" + mob.onGround() + " pos=" + mob.position() + " from=" + origin);
                // Behind the competing look goal and high enough to require a visible pitch change.
                player.setPos(mob.position().add(-12, 7, -3));
                MobReactions.handle(player, cue, 32);
            });
            for (int after : new int[]{7, 12, 16}) {
                test.runAfterDelay(begins + after, () -> {
                    test.assertTrue(!walk.running, "Ordinary walking immediately overwrote " + cue);
                    facing(test, mob, player.getEyePosition(), cue + " after " + after + " ticks");
                    calm(test, mob);
                });
            }
            // A second speaker during the same pause cannot redirect or prolong it.
            test.runAfterDelay(begins + 9, () -> MobReactions.handle(speaker(test, mob.position().add(10, 0, 0)), cue, 32));
            int expectedStarts = attempt + 2;
            test.runAfterDelay(begins + 25, () -> test.assertTrue(walk.running && walk.starts >= expectedStarts
                    && Math.abs(mob.getXRot()) < 10
                    && Math.abs(Mth.wrapDegrees(mob.getYHeadRot() - yaw(mob, player.getEyePosition()))) > 50,
                    "Movement/look controls failed to resume after " + cue + ": running=" + walk.running
                            + " starts=" + walk.starts + " pitch=" + mob.getXRot() + " head=" + mob.getYHeadRot()
                            + " callerYaw=" + yaw(mob, player.getEyePosition()) + " grounded=" + mob.onGround()));
        }
        test.runAfterDelay(89, test::succeed);
    }

    @GameTest(template = "hearing_floor", timeoutTicks = 200)
    public static void endermanDefaultRadiusAndModes(GameTestHelper test) { distance(test, EntityType.ENDERMAN, Cue.ENDERMAN); }

    @GameTest(template = "hearing_floor", timeoutTicks = 200)
    public static void spiderDefaultRadiusAndModes(GameTestHelper test) { distance(test, EntityType.CAVE_SPIDER, Cue.SPIDER); }

    private static void distance(GameTestHelper test, EntityType<? extends Mob> type, Cue named) {
        int when = 1;
        for (Cue cue : List.of(named, Cue.MOBS)) {
            for (int blocks : new int[]{16, 24, 31, 32, 33}) {
                int starts = when;
                when += 13;
                test.runAfterDelay(starts, () -> rangeCase(test, type, cue, blocks, GameType.SURVIVAL));
            }
        }
        for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
            test.runAfterDelay(when, () -> rangeCase(test, type, Cue.MOBS, 16, mode));
            when += 13;
        }
        test.runAfterDelay(when, test::succeed);
    }

    private static void rangeCase(GameTestHelper test, EntityType<? extends Mob> type, Cue cue, int blocks, GameType mode) {
        var mob = test.spawn(type, 6, 2, 5);
        Vec3 forward = mob.getEyePosition().add(0, 0, 4);
        mob.goalSelector.removeAllGoals(goal -> goal instanceof WaterAvoidingRandomStrollGoal);
        mob.goalSelector.addGoal(5, new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
            @Override public boolean canUse() { return true; }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void tick() { mob.getLookControl().setLookAt(forward.x, forward.y, forward.z, 90, 90); }
        });
        mob.setYRot(0);
        mob.yBodyRot = 0;
        mob.setYHeadRot(0);
        var player = speaker(test, mob.position().add(0, 0, -blocks));
        player.setGameMode(mode);
        test.assertTrue(player.distanceToSqr(mob) == blocks * blocks, "Fixture changed hearing distance");
        boolean heard = blocks <= 32 && mode == GameType.SURVIVAL;
        int originalGoalCount = mob.goalSelector.getAvailableGoals().size();
        MobReactions.handle(player, cue, 32);
        if (!heard) test.assertTrue(mob.goalSelector.getAvailableGoals().size() == originalGoalCount,
                "An excluded call installed a listening goal");
        test.startSequence().thenExecuteAfter(10, () -> {
            String label = cue + " at " + blocks + " in " + mode;
            if (heard) {
                facing(test, mob, player.getEyePosition(), label);
            } else {
                // Ordinary idle AI may glance sideways; the excluded caller stands directly behind.
                float error = Math.abs(Mth.wrapDegrees(mob.getYHeadRot() - yaw(mob, player.getEyePosition())));
                test.assertTrue(error > 60, label + " turned toward the excluded caller, yaw error=" + error);
            }
            calm(test, mob);
            mob.discard();
        });
    }

    @GameTest(template = "hearing_floor", batch = "voice_stare")
    public static void genuineEyeContactWinsDuringAGlance(GameTestHelper test) {
        var enderman = test.spawn(EntityType.ENDERMAN, 7, 2, 5);
        var listener = speaker(test, enderman.position().add(9, 3, 0));
        MobReactions.handle(listener, Cue.ENDERMAN, 32);
        test.runAfterDelay(7, () -> {
            facing(test, enderman, listener.getEyePosition(), "calm before eye contact");
            var watcher = speaker(test, enderman.position().add(-5, 0, 0));
            watcher.setYRot(yaw(watcher, enderman.getEyePosition()));
            watcher.setYHeadRot(watcher.getYRot()); // LivingEntity.getViewYRot(1) reads yHeadRot.
            watcher.setXRot(pitch(watcher, enderman.getEyePosition()));
            // Register a synthetic survival player so the real vanilla target selector can discover the stare.
            test.getLevel().addNewPlayer(watcher);
            test.succeedWhen(() -> {
                if (enderman.hasBeenStaredAt()) MobReactions.handle(listener, Cue.MOBS, 32);
                test.assertTrue(enderman.getTarget() == watcher && enderman.hasBeenStaredAt() && enderman.isCreepy(),
                        "Vanilla stare did not progress to its own target during the voice glance");
                MobReactions.handle(listener, Cue.ENDERMAN, 32);
                test.assertTrue(enderman.getTarget() == watcher && enderman.isCreepy(), "Voice replaced established eye-contact aggro");
                test.getLevel().removePlayerImmediately(watcher, Entity.RemovalReason.DISCARDED);
            });
        });
    }

    @GameTest(template = "hearing_floor")
    public static void combatAndPassiveGoalsKeepControl(GameTestHelper test) {
        var spider = test.spawn(EntityType.SPIDER, 6, 2, 6);
        var enderman = test.spawn(EntityType.ENDERMAN, 6, 2, 4);
        var cow = test.spawn(EntityType.COW, 4, 2, 5);
        var owner = speaker(test, test.absoluteVec(new Vec3(11.5, 2, 5.5)));
        var caller = speaker(test, test.absoluteVec(new Vec3(-7.5, 6, 5.5)));
        for (var mob : List.of(spider, enderman)) mob.setTarget(owner);
        for (var mob : List.of(spider, enderman, cow)) {
            float initial = yaw(mob, owner.getEyePosition());
            mob.setYRot(initial);
            mob.setYHeadRot(initial);
            mob.yBodyRot = initial;
            mob.getLookControl().setLookAt(owner, 45, 45);
        }
        var mobs = List.of(spider, enderman, cow);
        var goalsBefore = mobs.stream().map(mob -> mob.goalSelector.getAvailableGoals().size()).toList();
        for (Cue cue : List.of(Cue.SPIDER, Cue.ENDERMAN, Cue.MOBS)) MobReactions.handle(caller, cue, 32);
        test.assertTrue(spider.getTarget() == owner && enderman.getTarget() == owner && cow.getTarget() == null,
                "Voice stole or created a combat target");
        for (var mob : List.of(spider, enderman, cow)) {
            test.assertTrue(mob.getLookControl().getWantedX() == owner.getX(), "Voice interrupted combat or passive look");
        }
        test.runAfterDelay(8, () -> {
            for (var mob : List.of(spider, enderman)) {
                test.assertTrue(mob.getTarget() == owner, "An established target was pacified");
            }
            // A fighting mob can turn or teleport during eight native AI ticks. Measure the
            // actual effect of a second call from its current position and check both pose owners.
            for (int i = 0; i < mobs.size(); i++) {
                var mob = mobs.get(i);
                float head = mob.getYHeadRot(), body = mob.yBodyRot;
                var look = mob.getLookControl();
                var aim = new Vec3(look.getWantedX(), look.getWantedY(), look.getWantedZ());
                var route = mob.getNavigation().getPath();
                MobReactions.handle(caller, Cue.MOBS, 32);
                test.assertTrue(head == mob.getYHeadRot() && body == mob.yBodyRot
                        && aim.equals(new Vec3(look.getWantedX(), look.getWantedY(), look.getWantedZ()))
                        && mob.getNavigation().getPath() == route && !NamedMobReactions.isReacting(mob)
                        && mob.goalSelector.getAvailableGoals().size() == goalsBefore.get(i),
                        "Voice altered combat or passive behavior for " + mob.getType());
            }
            test.succeed();
        });
    }

    private static ServerPlayer speaker(GameTestHelper test, Vec3 position) {
        var player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), "GlanceFixture"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(position);
        return player;
    }

    private static void calm(GameTestHelper test, Mob mob) {
        test.assertTrue(mob.getTarget() == null, "Voice created an attack target");
        if (mob instanceof EnderMan enderman) {
            test.assertTrue(!enderman.isCreepy() && !enderman.hasBeenStaredAt() && !enderman.isAngry()
                    && enderman.getPersistentAngerTarget() == null, "Voice created Enderman anger");
        }
    }

    private static void facing(GameTestHelper test, Mob mob, Vec3 wanted, String label) {
        float yawError = Math.abs(Mth.wrapDegrees(mob.getYHeadRot() - yaw(mob, wanted)));
        float pitchError = Math.abs(mob.getXRot() - pitch(mob, wanted));
        test.assertTrue(yawError <= 15 && pitchError <= 7,
                label + " raw head yaw=" + mob.getYHeadRot() + " pitch=" + mob.getXRot()
                        + " errors=" + yawError + "/" + pitchError + " body=" + mob.yBodyRot);
    }

    private static float yaw(Entity mob, Vec3 point) {
        return (float) Math.toDegrees(Math.atan2(point.z - mob.getZ(), point.x - mob.getX())) - 90;
    }

    private static float pitch(Entity mob, Vec3 point) {
        Vec3 delta = point.subtract(mob.getEyePosition());
        return (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
    }

    private static final class WalkAndLook extends Goal {
        private final Mob mob;
        private final Vec3 destination;
        boolean running;
        int starts;
        WalkAndLook(Mob mob, Vec3 destination) {
            this.mob = mob;
            this.destination = destination;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override public boolean canUse() { return mob.onGround(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() {
            running = true;
            starts++;
            mob.getNavigation().moveTo(destination.x, destination.y, destination.z, 0.65);
        }
        @Override public void tick() {
            if (mob.getNavigation().isDone() && mob.distanceToSqr(destination) > 2)
                mob.getNavigation().moveTo(destination.x, destination.y, destination.z, 0.65);
            mob.getLookControl().setLookAt(mob.getX() + 10, mob.getEyeY(), mob.getZ(), 45, 45);
        }
        @Override public void stop() { running = false; mob.getNavigation().stop(); }
    }
}

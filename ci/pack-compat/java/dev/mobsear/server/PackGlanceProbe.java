package dev.mobsear.server;

import com.mojang.authlib.GameProfile;
import dev.mobsear.net.Cue;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Identical stable and candidate measurements. These are actual head angles after normal game ticks. */
@GameTestHolder("mobsear")
@PrefixGameTestTemplate(false)
public final class PackGlanceProbe {
    private static final String PROFILE = System.getProperty("mobsear.probe.profile");
    private static final String REVISION = System.getProperty("mobsear.probe.revision");
    private record Scenario(boolean spider, boolean walking, double distance, String call) {}

    @GameTest(template = "hearing_floor", batch = "pack_angles", timeoutTicks = 3000)
    public static void compareActualLooking(GameTestHelper test) {
        for (String id : List.of("aiimprovements", "enhancedai", "insanelib", "lithium", "dangerous", "buffmobs", "infernalmobs")) {
            test.assertTrue(ModList.get().isLoaded(id) == PROFILE.equals("ai"), "Incorrect probe mod set: " + id);
        }
        var cases = new ArrayList<Scenario>();
        for (boolean spider : List.of(false, true)) for (boolean walking : List.of(false, true)) {
            for (double distance : new double[]{4, 16, 24, 31.5, 33}) for (String call : List.of("quiet", "named", "mobs")) {
                cases.add(new Scenario(spider, walking, distance, call));
            }
        }
        new Trial(test).plan(cases);
    }

    private static final class Trial {
        final GameTestHelper test;
        final List<String> failures = new ArrayList<>();
        final ServerPlayer speaker;
        final double[] yaw = new double[23], pitch = new double[23];
        Mob mob;
        Vec3 born, source;
        double walkDistance;
        boolean requested, targetChanged;

        Trial(GameTestHelper test) {
            this.test = test;
            // Not present in the level's player list: natural nearest-player goals cannot identify
            // the test voice between rounds. Dedicated candidate gaze-aggro tests use a registered viewer.
            speaker = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), "ProbeVoice"));
            speaker.setGameMode(GameType.SURVIVAL);
        }

        void plan(List<Scenario> cases) {
            var sequence = test.startSequence();
            for (var c : cases) {
                sequence.thenExecute(() -> spawn(c)).thenIdle(18).thenExecute(() -> call(c));
                for (int t = 1; t < yaw.length; t++) {
                    int sample = t;
                    sequence.thenIdle(1).thenExecute(() -> observe(sample));
                }
                sequence.thenExecute(() -> finish(c)).thenIdle(1);
            }
            sequence.thenExecute(() -> {
                System.out.println("PACK_GLANCE_COMPLETE " + REVISION + " " + PROFILE + " rows=" + cases.size());
                test.assertTrue(failures.isEmpty(), "Failed candidate observations: " + failures);
            }).thenSucceed();
        }

        void spawn(Scenario c) {
            EntityType<? extends Mob> type = c.spider ? EntityType.SPIDER : EntityType.ENDERMAN;
            mob = type.spawn(test.getLevel(), test.absolutePos(new BlockPos(3, 2, 5)), MobSpawnType.COMMAND);
            test.assertTrue(mob != null, "Could not spawn probe entity through normal spawn hooks");
            mob.setPersistenceRequired();
            mob.setYRot(-90);
            mob.setYHeadRot(-90);
            mob.yBodyRot = -90;
            born = mob.position();
            if (c.walking) mob.goalSelector.addGoal(5, new WalkPast(mob, test.absoluteVec(new Vec3(11.5, 2, 5.5))));
        }

        void call(Scenario c) {
            walkDistance = mob.position().distanceTo(born);
            // Behind the walking subject, at the exact 3D distance measured from its current feet.
            // Small elevation also exercises actual pitch on both a tall and a short mob.
            Vec3 direction = new Vec3(-1, .22, -.08).normalize();
            speaker.setPos(mob.position().add(direction.scale(c.distance)));
            speaker.setYRot(90); // Looks away from the mob.
            source = speaker.getEyePosition();
            targetChanged = false;
            requested = false;
            observe(0);
            if (!c.call.equals("quiet")) {
                Cue cue = c.call.equals("mobs") ? Cue.MOBS : c.spider ? Cue.SPIDER : Cue.ENDERMAN;
                MobReactions.handle(speaker, cue, 32);
                var look = mob.getLookControl();
                requested = Math.abs(look.getWantedX() - source.x) < 1e-4
                        && Math.abs(look.getWantedZ() - source.z) < 1e-4;
            }
        }

        void observe(int tick) {
            Vec3 to = source.subtract(mob.getEyePosition());
            double wantYaw = Math.toDegrees(Math.atan2(to.z, to.x)) - 90;
            double wantPitch = -Math.toDegrees(Math.atan2(to.y, to.horizontalDistance()));
            yaw[tick] = Math.abs(Mth.wrapDegrees(mob.getYHeadRot() - wantYaw));
            pitch[tick] = Math.abs(mob.getXRot() - wantPitch);
            targetChanged |= mob.getTarget() != null || mob instanceof EnderMan ender
                    && (ender.isAngry() || ender.hasBeenStaredAt() || ender.isCreepy());
        }

        void finish(Scenario c) {
            int first = -1, aligned = 0, run = 0, longest = 0;
            for (int t = 1; t < yaw.length; t++) {
                if (yaw[t] <= 25 && pitch[t] <= 15) {
                    if (first == -1) first = t;
                    aligned++;
                    longest = Math.max(longest, ++run);
                } else run = 0;
            }
            System.out.printf(Locale.ROOT, "PACK_GLANCE revision=%s profile=%s mob=%s state=%s distance=%.1f cue=%s"
                            + " requested=%b targetChanged=%b moved=%.3f initialYaw=%.1f yaw4=%.1f yaw8=%.1f yaw12=%.1f"
                            + " yaw16=%.1f pitch8=%.1f alignedTicks=%d firstAligned=%d longest=%d controller=%s%n",
                    REVISION, PROFILE, c.spider ? "spider" : "enderman", c.walking ? "walking" : "ambient",
                    c.distance, c.call, requested, targetChanged, walkDistance, yaw[0], yaw[4], yaw[8], yaw[12], yaw[16],
                    pitch[8], aligned, first, longest, mob.getLookControl().getClass().getSimpleName());
            boolean inside = !c.call.equals("quiet") && c.distance <= 32;
            if (REVISION.equals("candidate") && (targetChanged || requested != inside ||
                    (inside && c.walking && (walkDistance <= 0.05 || longest < 6)))) {
                failures.add(c.toString());
            }
            mob.discard();
        }
    }

    /** A repeatable low-priority straight walk over the unobstructed floor uses normal movement physics. */
    private static final class WalkPast extends Goal {
        private final Mob mob;
        private final Vec3 destination;
        WalkPast(Mob mob, Vec3 destination) {
            this.mob = mob;
            this.destination = destination;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override public boolean canUse() { return mob.onGround(); }
        @Override public boolean canContinueToUse() { return true; }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            mob.getMoveControl().setWantedPosition(destination.x, mob.getY(), destination.z, 0.8);
            mob.getLookControl().setLookAt(destination.x, mob.getEyeY(), destination.z, 45, 45);
        }
        @Override public void stop() {
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
            mob.getNavigation().stop();
        }
    }
}

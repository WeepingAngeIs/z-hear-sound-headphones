package dev.mobsear.server;

import com.mojang.authlib.GameProfile;
import dev.mobsear.net.Cue;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mobsear")
@PrefixGameTestTemplate(false)
public final class MobReactionGameTests {
    @GameTest(template = "hearing_floor")
    public static void zombieFamilyAndRadius(GameTestHelper helper) {
        var at = helper.absoluteVec(new Vec3(5.5, 2, 5.5));
        var speaker = speaker(helper.getLevel(), at);
        var other = speaker(helper.getLevel(), at.add(0, 0, -2));
        var family = List.of(
                helper.spawn(EntityType.ZOMBIE, 6, 2, 5),
                helper.spawn(EntityType.HUSK, 5, 2, 6),
                helper.spawn(EntityType.DROWNED, 4, 2, 5),
                helper.spawn(EntityType.ZOMBIE_VILLAGER, 5, 2, 4),
                helper.spawn(EntityType.ZOMBIFIED_PIGLIN, 8, 2, 5));
        var diagonal = helper.spawn(EntityType.ZOMBIE, 8, 2, 8);
        var disabled = helper.spawn(EntityType.ZOMBIE, 4, 2, 4);
        disabled.setNoAi(true);
        var skeleton = helper.spawn(EntityType.SKELETON, 4, 2, 6);
        skeleton.getLookControl().setLookAt(skeleton.position());
        family.forEach(zombie -> zombie.setTarget(other));

        MobReactions.handle(speaker, Cue.ZOMBIE, 3);
        for (Zombie zombie : family) {
            helper.assertTrue(zombie.getTarget() == speaker, "Zombie family member failed to switch target");
            helper.assertTrue(lookingAt(zombie, speaker.getEyePosition()), "Zombie did not look at the voice");
        }
        helper.assertTrue(diagonal.getTarget() == null, "A zombie beyond the spherical radius heard the cue");
        helper.assertTrue(disabled.getTarget() == null, "A NoAI zombie changed target");
        helper.assertTrue(lookingAt(skeleton, skeleton.position()), "An unrelated hostile heard the zombie alias");
        helper.succeed();
    }

    @GameTest(template = "hearing_floor")
    public static void broadCueAndEnderman(GameTestHelper helper) {
        var speaker = speaker(helper.getLevel(), helper.absoluteVec(new Vec3(5.5, 2, 5.5)));
        var enderman = helper.spawn(EntityType.ENDERMAN, 7, 2, 5);
        var cow = helper.spawn(EntityType.COW, 6, 2, 4);
        var skeleton = helper.spawn(EntityType.SKELETON, 6, 2, 6);
        var slime = helper.spawn(EntityType.SLIME, 7, 2, 6);
        for (Mob mob : List.of(enderman, cow, skeleton, slime)) {
            mob.getLookControl().setLookAt(mob.position());
        }

        MobReactions.handle(speaker, Cue.ENDERMAN, 4);
        helper.assertTrue(lookingAt(enderman, speaker.getEyePosition()), "Named Enderman did not glance");
        assertCalm(helper, enderman);
        helper.assertTrue(lookingAt(skeleton, skeleton.position()), "Enderman cue affected a skeleton");

        MobReactions.handle(speaker, Cue.MOBS, 4);
        helper.assertTrue(lookingAt(skeleton, speaker.getEyePosition()), "Broad cue missed a skeleton");
        helper.assertTrue(lookingAt(slime, speaker.getEyePosition()), "Broad cue missed a slime");
        helper.assertTrue(lookingAt(cow, cow.position()) && cow.getTarget() == null, "Passive cow reacted");
        assertCalm(helper, enderman);

        var existingTarget = speaker(helper.getLevel(), speaker.position().add(-1, 0, 0));
        enderman.setTarget(existingTarget);
        MobReactions.handle(speaker, Cue.ENDERMAN, 4);
        helper.assertTrue(enderman.getTarget() == existingTarget, "Voice replaced an Enderman's existing target");
        helper.succeed();
    }

    @GameTest(template = "hearing_floor")
    public static void eligibilityAndDimension(GameTestHelper helper) {
        var at = helper.absoluteVec(new Vec3(5.5, 2, 5.5));
        var speaker = speaker(helper.getLevel(), at);
        var zombie = helper.spawn(EntityType.ZOMBIE, 6, 2, 5);
        for (GameType type : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
            speaker.setGameMode(type);
            MobReactions.handle(speaker, Cue.MOBS, 4);
            helper.assertTrue(zombie.getTarget() == null, "Ineligible player mode drew aggro");
        }
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Disposable test server needs a Nether dimension");
        var netherSpeaker = speaker(nether, at);
        MobReactions.handle(netherSpeaker, Cue.ZOMBIE, 4);
        helper.assertTrue(zombie.getTarget() == null, "Voice crossed dimensions");
        helper.succeed();
    }

    @GameTest(template = "hearing_floor")
    public static void zombiePausesThenResumes(GameTestHelper helper) {
        var speaker = speaker(helper.getLevel(), helper.absoluteVec(new Vec3(4.5, 2, 5.5)));
        var zombie = helper.spawn(EntityType.ZOMBIE, 7, 2, 5);
        var walk = new TestWalk(zombie, helper.absoluteVec(new Vec3(11.5, 2, 5.5)));
        zombie.goalSelector.addGoal(1, walk);
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(walk.running, "Fixture walking goal did not start");
            MobReactions.handle(speaker, Cue.ZOMBIE, 10);
        });
        helper.runAfterDelay(9, () -> {
            helper.assertTrue(!walk.running && zombie.getNavigation().isDone(), "Zombie did not pause movement");
            helper.assertTrue(lookingAt(zombie, speaker.getEyePosition()), "Zombie did not hold its look");
        });
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(walk.running, "Zombie did not return movement control to its normal goals");
            helper.succeed();
        });
    }

    private static void assertCalm(GameTestHelper helper, EnderMan enderman) {
        helper.assertTrue(enderman.getTarget() == null && !enderman.isCreepy() && !enderman.hasBeenStaredAt()
                && enderman.getPersistentAngerTarget() == null,
                "A voice cue changed the Enderman's anger or vanilla stare state");
    }

    private static boolean lookingAt(Mob mob, Vec3 pos) {
        var look = mob.getLookControl();
        return look.getWantedX() == pos.x && look.getWantedY() == pos.y && look.getWantedZ() == pos.z;
    }

    private static ServerPlayer speaker(ServerLevel level, Vec3 at) {
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "FixtureSpeaker"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(at);
        return player;
    }

    private static final class TestWalk extends Goal {
        private final Zombie zombie;
        private final Vec3 destination;
        private boolean running;

        TestWalk(Zombie zombie, Vec3 destination) {
            this.zombie = zombie;
            this.destination = destination;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override public boolean canUse() { return true; }

        @Override public void start() {
            running = true;
            zombie.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.0);
        }

        @Override public void stop() {
            running = false;
            zombie.getNavigation().stop();
        }
    }
}

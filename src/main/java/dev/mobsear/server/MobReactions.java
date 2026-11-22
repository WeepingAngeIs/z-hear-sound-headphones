package dev.mobsear.server;

import dev.mobsear.net.Cue;
import java.util.EnumSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

public final class MobReactions {
    private MobReactions() {}

    /** Called on the server thread after the voice session accepts an allowlisted cue. */
    public static void handle(ServerPlayer speaker, Cue cue, double radius) {
        react(speaker, cue, radius);
    }

    static ResourceLocation react(ServerPlayer speaker, Cue cue, double radius) {
        if (speaker.isRemoved() || speaker.isCreative() || !speaker.canBeSeenAsEnemy()
                || !Double.isFinite(radius) || radius <= 0) {
            return null;
        }
        ServerLevel level = speaker.serverLevel();
        double distanceLimit = radius * radius;
        var area = speaker.getBoundingBox().inflate(radius);
        Mob nearest = null;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, mob ->
                mob instanceof Enemy && mob.isAlive() && !mob.isNoAi()
                    && !mob.isAlliedTo(speaker) && mob.canAttack(speaker)
                    && mob.distanceToSqr(speaker) <= distanceLimit && matches(mob, cue))) {
            if (reactToSpeaker(mob, speaker, distanceLimit)
                    && (nearest == null || mob.distanceToSqr(speaker) < nearest.distanceToSqr(speaker))) nearest = mob;
        }
        return nearest == null ? null : NamedMobReactions.listenerName(nearest);
    }

    private static boolean reactToSpeaker(Mob mob, ServerPlayer speaker, double distanceLimit) {
        // Vanilla still owns eye contact, targets, retaliation and escape.
        if (mob instanceof EnderMan || mob instanceof Spider)
            return calm(mob) && listenGoal(mob).hear(speaker.getEyePosition());
        if (!(mob instanceof Zombie zombie)) return NamedMobReactions.glanceIfCalm(speaker, mob, distanceLimit);
        zombie.lookAt(speaker, 45.0F, 45.0F);
        zombie.getLookControl().setLookAt(speaker, 45.0F, 45.0F);
        boolean heard = listenGoal(zombie).hear(speaker.getEyePosition());
        if (zombie.getTarget() != speaker && zombie.isWithinRestriction(speaker.blockPosition())) {
            zombie.setTarget(speaker);
            return heard || zombie.getTarget() == speaker;
        }
        return heard;
    }

    private static boolean matches(Mob mob, Cue cue) {
        return switch (cue) {
            case ZOMBIE -> mob instanceof Zombie; // Includes husks, drowned, villagers and zombified piglins.
            case ENDERMAN -> mob instanceof EnderMan;
            case SKELETON -> mob instanceof net.minecraft.world.entity.monster.AbstractSkeleton;
            case SPIDER -> mob instanceof net.minecraft.world.entity.monster.Spider;
            case GUARDIAN -> mob instanceof net.minecraft.world.entity.monster.Guardian;
            case SLIME -> mob instanceof net.minecraft.world.entity.monster.Slime;
            case MOBS -> true;
            default -> {
                var type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
                yield type.getNamespace().equals("minecraft") && type.getPath().equals(cue.entityPath);
            }
        };
    }

    private static boolean calm(Mob mob) {
        return !mob.isPassenger() && !mob.isVehicle() && mob.getTarget() == null && (!(mob instanceof EnderMan enderman)
                || (!enderman.hasBeenStaredAt() && !enderman.isCreepy() && !enderman.isAngry()));
    }

    private static ListenGoal listenGoal(Mob mob) {
        for (WrappedGoal existing : mob.goalSelector.getAvailableGoals()) {
            if (existing.getGoal() instanceof ListenGoal listen) {
                return listen;
            }
        }
        ListenGoal listen = new ListenGoal(mob);
        // Idle species can interrupt ordinary strolling or looking. Float, flee and stare goals retain precedence.
        mob.goalSelector.addGoal(mob instanceof Zombie ? 0 : 4, listen);
        return listen;
    }

    /** Owns MOVE and LOOK just long enough for a visible turn. No player reference survives the sound. */
    private static final class ListenGoal extends Goal {
        private final Mob mob;
        private Vec3 heardAt = Vec3.ZERO;
        private long pauseUntil;
        private long nextPause;

        ListenGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        boolean hear(Vec3 position) {
            long now = mob.level().getGameTime();
            if (now < nextPause) {
                if (mob instanceof Zombie) heardAt = position;
                return false;
            }
            heardAt = position;
            pauseUntil = now + (mob instanceof Zombie ? 10 : 18);
            nextPause = now + 40;
            if (mob instanceof Zombie) mob.getNavigation().stop();
            mob.getLookControl().setLookAt(heardAt.x, heardAt.y, heardAt.z, 45.0F, 45.0F);
            return true;
        }

        @Override
        public boolean canUse() {
            return mob.level().getGameTime() < pauseUntil && (mob instanceof Zombie || calm(mob));
        }

        @Override
        public void start() {
            // A climbing spider keeps a fallback destination after PathNavigation.stop() clears its path.
            // Let the navigation controller finish that fallback at its current feet.
            var navigation = mob.getNavigation();
            if (navigation instanceof WallClimberNavigation) navigation.moveTo(mob.getX(), mob.getY(), mob.getZ(), 0);
            navigation.stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            // Vanilla can acquire a target on the same tick before this goal's next selector cleanup.
            if (!canUse()) return;
            mob.getNavigation().stop();
            if (!(mob instanceof Zombie)) {
                // A moving spider's head is clamped relative to its body. Turn the stationary
                // body as well, so a sound behind it can produce the same visible glance.
                mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
                float toward = (float)Math.toDegrees(Math.atan2(heardAt.z - mob.getZ(), heardAt.x - mob.getX())) - 90F;
                mob.setYRot(Mth.approachDegrees(mob.getYRot(), toward, 35F));
                mob.yBodyRot = Mth.approachDegrees(mob.yBodyRot, toward, 35F);
            }
            mob.getLookControl().setLookAt(heardAt.x, heardAt.y, heardAt.z, 45.0F, 45.0F);
        }

        @Override
        public void stop() {
            if (!(mob instanceof Zombie)) {
                pauseUntil = 0;
                heardAt = Vec3.ZERO;
            }
        }
    }
}

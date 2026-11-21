package dev.mobsear.server;

import dev.mobsear.net.Cue;
import java.util.EnumSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

public final class MobReactions {
    private MobReactions() {}

    /** Called on the server thread after the voice session accepts an allowlisted cue. */
    public static void handle(ServerPlayer speaker, Cue cue, double radius) {
        if (speaker.isRemoved() || speaker.isCreative() || !speaker.canBeSeenAsEnemy()
                || !Double.isFinite(radius) || radius <= 0) {
            return;
        }
        ServerLevel level = speaker.serverLevel();
        double distanceLimit = radius * radius;
        var area = speaker.getBoundingBox().inflate(radius);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, mob ->
                mob instanceof Enemy && mob.isAlive() && !mob.isNoAi()
                    && !mob.isAlliedTo(speaker) && mob.canAttack(speaker)
                    && mob.distanceToSqr(speaker) <= distanceLimit && matches(mob, cue))) {
            mob.lookAt(speaker, 45.0F, 45.0F);
            mob.getLookControl().setLookAt(speaker, 45.0F, 45.0F);
            if (mob instanceof Zombie zombie) {
                listenGoal(zombie).hear(speaker.getEyePosition());
                if (zombie.getTarget() != speaker && zombie.isWithinRestriction(speaker.blockPosition())) {
                    zombie.setTarget(speaker);
                }
            }
        }
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

    private static ListenGoal listenGoal(Zombie zombie) {
        for (WrappedGoal existing : zombie.goalSelector.getAvailableGoals()) {
            if (existing.getGoal() instanceof ListenGoal listen) {
                return listen;
            }
        }
        ListenGoal listen = new ListenGoal(zombie);
        zombie.goalSelector.addGoal(0, listen);
        return listen;
    }

    /** Keeps vanilla chase goals from immediately replacing the pause on the next tick. */
    private static final class ListenGoal extends Goal {
        private final Zombie zombie;
        private Vec3 heardAt = Vec3.ZERO;
        private long pauseUntil;
        private long nextPause;

        ListenGoal(Zombie zombie) {
            this.zombie = zombie;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        void hear(Vec3 position) {
            heardAt = position;
            long now = zombie.level().getGameTime();
            if (now >= nextPause) {
                pauseUntil = now + 10;
                nextPause = now + 40;
                zombie.getNavigation().stop();
            }
        }

        @Override
        public boolean canUse() {
            return zombie.level().getGameTime() < pauseUntil;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            zombie.getNavigation().stop();
            zombie.getLookControl().setLookAt(heardAt.x, heardAt.y, heardAt.z, 45.0F, 45.0F);
        }
    }
}

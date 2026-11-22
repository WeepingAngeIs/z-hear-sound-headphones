package dev.mobsear.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.phys.Vec3;

/** Vanilla strolling owns the gait, path, speed and heading. Only its random destination is fixed. */
final class WalkingDeer {
    private static final BlockPos DESTINATION = new BlockPos(-18, 104, 8);
    private final PathfinderMob deer;
    private final RandomStrollGoal stroll;
    private double cueX, callDistance, previousX, lastActiveX;
    private int movingTicks;

    WalkingDeer(PathfinderMob deer) {
        this.deer = deer;
        stroll = new RandomStrollGoal(deer, 0.8) {
            @Override protected Vec3 getPosition() { return Vec3.atBottomCenterOf(DESTINATION); }
        };
        deer.goalSelector.addGoal(0, stroll);
        stroll.trigger();
    }

    private boolean onRoute() {
        var path = deer.getNavigation().getPath();
        return path != null && !path.isDone() && DESTINATION.equals(path.getTarget())
                && deer.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() == stroll && g.isRunning());
    }

    boolean started() {
        return onRoute() && deer.getX() < 0.10 && deer.getX() < deer.xo - .03;
    }

    void atCall(ServerPlayer caller) {
        if (!started() || deer.getTarget() != null) throw PokemonProbe.fail("Deer was not walking away at the call");
        cueX = previousX = lastActiveX = deer.getX();
        callDistance = deer.distanceTo(caller);
    }

    void observe(boolean active) {
        if (!onRoute() || deer.getTarget() != null)
            throw PokemonProbe.fail("Deer lost its native walking goal, destination or neutrality after the call");
        if (active) {
            if (previousX - deer.getX() > .025) movingTicks++;
            lastActiveX = deer.getX();
        }
        previousX = deer.getX();
    }

    boolean verify() {
        if (movingTicks < 8 || travelDuring() < .65)
            throw PokemonProbe.fail("Deer did not keep walking during the glance: ticks="+movingTicks+" travel="+travelDuring());
        if (lastActiveX - deer.getX() < .15) return false;
        System.out.printf(java.util.Locale.ROOT,
                "ENTITY_DEER_ROUTE nativeGoal=true distanceAtCall=%.2f movingTicks=%d travelDuring=%.2f travelAfter=%.2f target=none%n",
                callDistance, movingTicks, travelDuring(), lastActiveX-deer.getX());
        return true;
    }

    double travelDuring() { return cueX-lastActiveX; }
}

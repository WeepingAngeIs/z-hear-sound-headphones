package dev.mobsear.fixture;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

/** Measure the real creature's travel, orientation and rest on the running server. */
public final class CalledPokemonVisit {
    private final PokemonEntity mob;
    private final ServerPlayer caller;
    private final Vec3 initial;
    private final BlockPos wanderTarget;
    private Vec3 previous, rested;
    private int stopped, bestStop, nearTicks, arrivedAt = -1, releaseTick = -1, nativeRouteTick = -1;
    private boolean movedCaller, approached, resumed;
    private double beforeCall;

    public CalledPokemonVisit(PokemonEntity mob, ServerPlayer caller, int wanderBlocks) {
        this.mob = mob;
        this.caller = caller;
        initial = previous = mob.position();
        wanderTarget = mob.blockPosition().offset(0,0,wanderBlocks);
        var nav = mob.getNavigation();
        var path = nav.createPath(wanderTarget, 0);
        if (path == null || !path.canReach() || !nav.moveTo(path,.8))
            throw new IllegalStateException("Missing pre-call native wander");
        mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(wanderTarget,.8F,0));
    }

    public boolean isWandering() {
        var path = mob.getNavigation().getPath();
        beforeCall = mob.position().distanceTo(initial);
        previous = mob.position();
        return beforeCall >= .45 && !mob.getNavigation().isDone() && path != null && wanderTarget.equals(path.getTarget());
    }

    public boolean observe(boolean active, boolean moveCaller, int tick) {
        if (resumed) return true;
        var position = mob.position();
        var delta = position.subtract(previous);
        previous = position;
        if (delta.length() > 1.4) throw new IllegalStateException("Pokémon jumped between positions " + delta);
        double distance = mob.distanceTo(caller);
        double yaw = Math.toDegrees(Math.atan2(caller.getZ()-mob.getZ(),caller.getX()-mob.getX()))-90;
        double error = Math.abs(Mth.wrapDegrees(mob.getYHeadRot()-yaw));
        if (active) {
            if (!approached) {
                stopped = delta.horizontalDistance() < .045 && error < 30 && mob.getNavigation().isDone() ? stopped+1 : 0;
                bestStop = Math.max(stopped,bestStop);
                if (position.x-initial.x > .9) {
                    if (bestStop < 3) throw new IllegalStateException("Approach started without a visible stationary turn: " + bestStop);
                    approached = true;
                }
            }
            if (approached && moveCaller && !movedCaller) {
                movedCaller=true;
                caller.teleportTo(caller.getX()+1,caller.getY(),caller.getZ()-5);
            }
            if (approached && distance<=2.55 && mob.getNavigation().isDone()) {
                if (rested==null) { rested=position; arrivedAt=tick; }
                if (rested.distanceTo(position)>.48) throw new IllegalStateException("Pokémon drifted away during its stay");
                nearTicks++;
            } else if (rested!=null) throw new IllegalStateException("Pokémon left its caller before finishing the stay");
        } else if (rested!=null) {
            if (releaseTick<0) {
                if (tick-arrivedAt<40 || nearTicks<40 || distance>2.55 || rested.distanceTo(position)>.48)
                    throw new IllegalStateException("Early release: held intervals="+nearTicks+" distance="+distance);
                releaseTick=tick;
            }
            // Observe only. The Pokémon must select a new native route and travel along it unaided.
            if (nativeRouteTick<0 && !mob.getNavigation().isDone() && mob.getNavigation().getPath()!=null)
                nativeRouteTick=tick;
            if (nativeRouteTick>=0 && position.distanceTo(rested)>.65) resumed=true;
        }
        if (resumed) System.out.printf(java.util.Locale.ROOT,
                "POKEMON_VISIT_PASS species=%s wanderBefore=%.2f stoppedFacingTicks=%d approached=true callerMoved=%s staySamples=%d besideAtRelease=true unaided=true nativeRouteAfterTicks=%d nativeTravelAfter=%.2f%n",
                mob.getPokemon().getSpecies().getResourceIdentifier(),beforeCall,bestStop,movedCaller,nearTicks,
                nativeRouteTick-releaseTick,position.distanceTo(rested));
        return resumed;
    }

    public boolean stayedTwoSeconds() { return nearTicks >= 40; }
}

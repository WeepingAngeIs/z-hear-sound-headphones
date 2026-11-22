package dev.mobsear.server;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.behavior.RandomStroll;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

/** Measure the real creature's travel, orientation and rest on the running server. */
final class CalledPokemonVisit {
    private final PokemonEntity mob;
    private final ServerPlayer caller;
    private final Vec3 initial;
    private final BlockPos wanderTarget;
    private Vec3 previous, rested;
    private int stopped, bestStop, nearTicks, arrivedAt = -1, releaseTick = -1;
    private boolean movedCaller, approached, resumed;
    private double beforeCall;

    CalledPokemonVisit(PokemonEntity mob, ServerPlayer caller) {
        this.mob = mob;
        this.caller = caller;
        initial = previous = mob.position();
        wanderTarget = mob.blockPosition().offset(0,0,8);
        var nav = mob.getNavigation();
        var path = nav.createPath(wanderTarget, 0);
        if (path == null || !path.canReach() || !nav.moveTo(path,.8))
            throw PokemonProbe.fail("Missing pre-call native wander");
        mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(wanderTarget,.8F,0));
    }

    boolean isWandering() {
        var path = mob.getNavigation().getPath();
        beforeCall = mob.position().distanceTo(initial);
        previous = mob.position();
        return beforeCall >= .45 && !mob.getNavigation().isDone() && path != null && wanderTarget.equals(path.getTarget());
    }

    boolean observe(boolean active, boolean moveCaller, int tick) {
        var position = mob.position();
        var delta = position.subtract(previous);
        previous = position;
        if (delta.length() > 1.4) throw PokemonProbe.fail("Pokémon jumped between positions " + delta);
        double distance = mob.distanceTo(caller);
        double yaw = Math.toDegrees(Math.atan2(caller.getZ()-mob.getZ(),caller.getX()-mob.getX()))-90;
        double error = Math.abs(Mth.wrapDegrees(mob.getYHeadRot()-yaw));
        if (active) {
            if (!approached) {
                stopped = delta.horizontalDistance() < .045 && error < 30 && mob.getNavigation().isDone() ? stopped+1 : 0;
                bestStop = Math.max(stopped,bestStop);
                if (position.x-initial.x > .9) {
                    if (bestStop < 3) throw PokemonProbe.fail("Approach started without a visible stationary turn: " + bestStop);
                    approached = true;
                }
            }
            if (approached && moveCaller && !movedCaller) {
                movedCaller=true;
                caller.teleportTo(caller.getX()+1,caller.getY(),caller.getZ()-5);
            }
            if (approached && distance<=2.55 && mob.getNavigation().isDone()) {
                if (rested==null) { rested=position; arrivedAt=tick; }
                if (rested.distanceTo(position)>.48) throw PokemonProbe.fail("Pokémon drifted away during its stay");
                nearTicks++;
            } else if (rested!=null) throw PokemonProbe.fail("Pokémon left its caller before finishing the stay");
        } else if (rested!=null) {
            if (releaseTick<0) {
                if (tick-arrivedAt<40 || nearTicks<40 || distance>2.55 || rested.distanceTo(position)>.48)
                    throw PokemonProbe.fail("Early release: held intervals="+nearTicks+" distance="+distance);
                releaseTick=tick;
            }
            if (position.distanceTo(rested)>.65) resumed=true;
            // If the normal idle schedule has not picked a walk yet, exercise the game's native idle behavior.
            if (!resumed && tick-releaseTick>=30 && tick%10==0 && mob.getNavigation().isDone()
                    && !mob.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET))
                RandomStroll.stroll(.8F).tryStart(caller.serverLevel(),mob,caller.serverLevel().getGameTime());
        }
        if (resumed) System.out.printf(java.util.Locale.ROOT,
                "POKEMON_VISIT_PASS species=%s wanderBefore=%.2f stoppedFacingTicks=%d approached=true callerMoved=%s staySamples=%d besideAtRelease=true nativeTravelAfter=%.2f%n",
                mob.getPokemon().getSpecies().getResourceIdentifier(),beforeCall,bestStop,movedCaller,nearTicks,position.distanceTo(rested));
        return resumed;
    }
}

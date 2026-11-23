package dev.mobsear.packprobe;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Moves the same two real Pokémon and observes the ordered list actually offered to the local speech worker. */
final class LiveNearbyProof {
    private final double[][] distances={{36,38},{31.5,32.5},{24,16},{16,24},{33,37},{18,16}};
    private final List<List<String>> expected=List.of(List.of(),List.of("nidorina"),List.of("nidorino","nidorina"),
            List.of("nidorina","nidorino"),List.of(),List.of("nidorina"));
    private final PokemonEntity[] subjects=new PokemonEntity[2];
    private volatile int stage=-1, observed=-1;
    private volatile long began;
    private volatile List<String> lastSeen=List.of();
    private boolean complete;
    private boolean missed;

    boolean server(ServerPlayer player) {
        if (complete) return true;
        var level=player.serverLevel();
        if (stage<0) {
            player.setGameMode(GameType.SURVIVAL);
            level.setDayTime(1000);
            for (int x=-1;x<=40;x++) for(int z=-2;z<=2;z++) level.setBlockAndUpdate(new BlockPos(x,159,z),Blocks.SEA_LANTERN.defaultBlockState());
            player.teleportTo(level,.5,160,.5,-90,0);
            for (int i=0;i<2;i++) {
                var entity=PokemonProperties.Companion.parse((i==0?"nidorina":"nidorino")+" level=15").createEntity(level);
                entity.moveTo(.5+distances[0][i],160,.5,90,0); entity.setNoGravity(true); entity.setPersistenceRequired();
                if (!level.addFreshEntity(entity) || !entity.getPokemon().isWild()) throw new IllegalStateException("Missing real wild test species");
                subjects[i]=entity;
            }
            began=System.nanoTime(); stage=0;
        } else if (observed==stage) {
            if (stage+1==distances.length) {
                for (var mob:subjects) mob.discard();
                complete=true;
                if (!missed) System.out.println("ACOUSTIC_ROSTER_PASS entry exit distance-order-reversal incompatible-AI same individuals same connection");
                return true;
            }
            began=System.nanoTime(); stage++;
        }
        if (player.distanceToSqr(.5,160,.5)>.04) player.teleportTo(level,.5,160,.5,-90,0);
        for(int i=0;i<subjects.length;i++){
            var mob=subjects[i];
            mob.setNoAi(i==1 && stage==5);
            prepareIdle(mob,.5+distances[stage][i],.5);
        }
        if (System.nanoTime()-began>12_000_000_000L) {
            missed=true;
            System.out.println("ACOUSTIC_ROSTER_MISS stage="+stage+" actual="+lastSeen+" caller="+player.position());
            for (var mob:subjects) System.out.println("ACOUSTIC_ROSTER_SUBJECT "+mob.getPokemon().getSpecies().getResourceIdentifier()+
                    " actualDistance="+mob.distanceTo(player)+" inWorld="+(level.getEntity(mob.getUUID())==mob)+" removed="+mob.isRemoved()+
                    " sleeping="+mob.isSleeping()+" noAi="+mob.isNoAi()+" busy="+mob.isBusy()+" wild="+mob.getPokemon().isWild()+
                    " aggressive="+mob.isAggressive()+" activity="+mob.getBrain().getActiveNonCoreActivity()+" target="+(mob.getTarget()!=null));
            observed=stage;
        }
        return false;
    }

    boolean passed() { return complete && !missed; }

    static void prepareIdle(PokemonEntity mob,double x,double z) {
        mob.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        mob.getBrain().setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.IDLE);
        mob.getNavigation().stop();
        mob.setDeltaMovement(Vec3.ZERO);
        mob.moveTo(x,160,z,90,0);
        mob.setYHeadRot(90);
        mob.setYBodyRot(90);
    }

    void client() throws ReflectiveOperationException {
        int phase=stage;
        if (phase<0 || phase==observed || System.nanoTime()-began<1_200_000_000L) return;
        var session=Boundary.get(Boundary.client(),"session");
        if (session==null || (long)Boundary.get(session,"lastRosterTick")==Long.MIN_VALUE) return;
        var speech=Boundary.get(session,"speech");
        var roster=(List<?>)Boundary.get(speech,"nearbySpecies");
        var found=roster.stream().map(Object::toString).filter(name->name.equals("cobblemon:nidorina")||name.equals("cobblemon:nidorino"))
                .map(name->name.substring("cobblemon:".length())).toList();
        lastSeen=found;
        if (found.equals(expected.get(phase))) {
            System.out.println("ACOUSTIC_ROSTER stage="+phase+" at="+java.util.Arrays.toString(distances[phase])+" received="+found);
            observed=phase;
        }
    }

    static boolean has(String species) throws ReflectiveOperationException {
        var speech=Boundary.get(Boundary.get(Boundary.client(),"session"),"speech");
        return ((List<?>)Boundary.get(speech,"nearbySpecies")).contains(species);
    }
}

package dev.mobsear.server;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.mobsear.net.Packets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-side species calls. Loaded only when Cobblemon is present. */
final class CobblemonCalls {
    private static final ResourceLocation HERD_ACTIVITY = ResourceLocation.fromNamespaceAndPath("cobblemon", "pokemon_herd");
    private static final int FACE_TICKS = 14;
    private static final int STUCK_TICKS = 100;
    private static final float WALK_SPEED = 1.0F;
    private static final Map<PokemonEntity, Response> responses = new IdentityHashMap<>();

    private CobblemonCalls() {}

    static boolean isPokemon(Mob mob) { return mob instanceof PokemonEntity; }
    static boolean isReacting(Mob mob) { return responses.containsKey(mob); }
    static boolean hasResponsePath(Mob mob) {
        var response = responses.get(mob);
        return response != null && response.stage == Stage.WALK && !mob.getNavigation().isDone();
    }

    static void register() {
        VisitInteractions.register();
        NeoForge.EVENT_BUS.addListener(CobblemonCalls::tick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            responses.clear();
        });
    }

    static boolean hear(ServerPlayer caller, ResourceLocation id, double radius) {
        var species = PokemonSpecies.getByIdentifier(id);
        if (species == null || !id.equals(species.getResourceIdentifier())) return false;
        var level = caller.serverLevel();
        long now = level.getServer().overworld().getGameTime();
        double limit = radius * radius;
        boolean heard = false;
        for (var mob : level.getEntitiesOfClass(PokemonEntity.class, caller.getBoundingBox().inflate(radius),
                mob -> mob.distanceToSqr(caller) <= limit && id.equals(mob.getPokemon().getSpecies().getResourceIdentifier())
                        && canAnswer(mob))) {
            var response = new Response(mob, caller, limit, now);
            responses.put(mob, response);
            response.standAndFace(mob);
            heard = true;
        }
        return heard;
    }

    private static boolean canAnswer(PokemonEntity mob) {
        return !responses.containsKey(mob) && available(mob);
    }

    static List<ResourceLocation> nearbySpecies(ServerPlayer caller, int radius) {
        var level = caller.serverLevel();
        var nearest = new HashMap<ResourceLocation, Double>();
        for (var mob : level.getEntitiesOfClass(PokemonEntity.class, caller.getBoundingBox().inflate(radius),
                mob -> mob.distanceToSqr(caller) <= (double) radius * radius && canAnswer(mob))) {
            var id = mob.getPokemon().getSpecies().getResourceIdentifier();
            if (id.toString().length() <= dev.mobsear.net.Packets.MAX_CALL_ID_LENGTH)
                nearest.merge(id, mob.distanceToSqr(caller), Math::min);
        }
        return nearest.entrySet().stream().sorted(Map.Entry.<ResourceLocation, Double>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey())).limit(dev.mobsear.net.Packets.NearbySpecies.MAX_SPECIES)
                .map(Map.Entry::getKey).toList();
    }

    static boolean visiting(PokemonEntity mob, ServerPlayer caller) {
        var response = responses.get(mob);
        if (response == null || response.caller != caller || response.stage != Stage.STAY || !available(mob)
                || !ServerVoice.eligible(caller)
                || caller.level() != mob.level() || !response.beside(mob)) return false;
        return !response.visitExpired(caller.serverLevel().getServer().overworld().getGameTime());
    }

    static boolean available(PokemonEntity mob) {
        var pokemon = mob.getPokemon();
        var brain = mob.getBrain();
        var activity = brain.getActiveNonCoreActivity().orElse(Activity.IDLE);
        return mob.isAlive() && !mob.isRemoved() && !mob.isNoAi() && !mob.isSleeping()
                && !mob.isPassenger() && !mob.isVehicle() && !mob.isLeashed()
                && pokemon.isWild() && !mob.isTame() && mob.getOwnerUUID() == null
                && pokemon.getOwnerUUID() == null && mob.getTethering() == null && pokemon.getTetheringId() == null
                && !pokemon.isFainted() && !mob.isBusy() && !mob.isBattling() && !mob.isBattleClone()
                && !mob.isEvolving() && mob.getBeamMode() == 0 && mob.getPhasingTargetId() == -1 && !mob.getQueuedToDespawn()
                && !mob.isAggressive() && mob.getTarget() == null && mob.getLastHurtByMob() == null
                // A calm follower can visit its caller while retaining its native herd leader.
                && (activity == Activity.IDLE || HERD_ACTIVITY.equals(BuiltInRegistries.ACTIVITY.getKey(activity)))
                && !brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && !brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                && !brain.hasMemoryValue(MemoryModuleType.IS_PANICKING)
                && !brain.hasMemoryValue(MemoryModuleType.BREED_TARGET);
    }

    private static void tick(ServerTickEvent.Post event) {
        long now = event.getServer().overworld().getGameTime();
        responses.entrySet().removeIf(entry -> {
            var mob = entry.getKey();
            var response = entry.getValue();
            if (response.update(mob, now)) return false;
            response.release(mob);
            return true;
        });
    }

    private enum Stage { FACE, WALK, STAY }

    /** Each response follows the player who made the call for its whole lifetime. */
    private static final class Response {
        final ServerPlayer caller;
        final double limit;
        final long started, deadline;
        final EntityTracker eyes;
        final WalkTarget approach;
        WalkTarget stand;
        Path assignedPath;
        Stage stage = Stage.FACE;
        long firstArrival = -1;
        long nextPath, lastProgress;
        Vec3 progressPosition;

        Response(PokemonEntity mob, ServerPlayer caller, double limit, long now) {
            this.caller = caller;
            this.limit = limit;
            started = lastProgress = now;
            deadline = now + Math.max(400, (long) Math.ceil(Math.sqrt(limit) * 30));
            eyes = new EntityTracker(caller, true);
            approach = new WalkTarget(new EntityTracker(caller, false), WALK_SPEED, 1);
            progressPosition = mob.position();
        }

        boolean update(PokemonEntity mob, long now) {
            if (visitExpired(now) || !available(mob) || !ServerVoice.eligible(caller)
                    || mob.level() != caller.level() || mob.distanceToSqr(caller) > limit) return false;
            if (stage == Stage.FACE && now - started < FACE_TICKS) {
                standAndFace(mob);
                return true;
            }
            // Stepping away and back during one call never restarts its six-second window.
            if (beside(mob)) {
                if (stage != Stage.STAY) {
                    stage = Stage.STAY;
                    if (firstArrival < 0) firstArrival = now;
                    VisitInteractions.status(mob, caller, true);
                }
                standAndFace(mob);
                VisitInteractions.collect(mob, caller);
                return true;
            }
            BlockPos destination = caller.blockPosition();
            if (now >= deadline || !mob.level().hasChunkAt(destination)
                    || !mob.level().getWorldBorder().isWithinBounds(destination) || !mob.isWithinRestriction(destination)) return false;
            if (stage != Stage.WALK) {
                if (stage == Stage.STAY) VisitInteractions.status(mob, caller, false);
                stage = Stage.WALK;
                lastProgress = now;
                progressPosition = mob.position();
                nextPath = now;
            }
            if (mob.position().distanceToSqr(progressPosition) >= 0.09) {
                progressPosition = mob.position();
                lastProgress = now;
            }
            if (now - lastProgress >= STUCK_TICKS) return false;
            walk(mob, now, destination);
            return true;
        }

        private boolean beside(PokemonEntity mob) {
            double reach = Math.max(2.5, (mob.getBbWidth() + caller.getBbWidth()) * 0.5 + 1.0);
            return mob.distanceToSqr(caller) <= reach * reach && mob.hasLineOfSight(caller);
        }

        private boolean visitExpired(long now) {
            return firstArrival >= 0 && now - firstArrival >= Packets.Visit.WINDOW_TICKS;
        }

        private void standAndFace(PokemonEntity mob) {
            var brain = mob.getBrain();
            var nav = mob.getNavigation();
            // Only idle movement reaches here. Keeping our stationary target in the brain
            // prevents a random stroll from restarting between the pause and the approach.
            if (stand == null || !stand.getTarget().currentBlockPosition().equals(mob.blockPosition()))
                stand = new WalkTarget(mob.blockPosition(), 0, 1);
            brain.setMemory(MemoryModuleType.WALK_TARGET, stand);
            if (!nav.isDone()) nav.stop();
            assignedPath = null;
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
            mob.setSpeed(0);
            // A stopped navigation path can leave horizontal momentum from the last walking tick.
            if (mob.onGround() && mob.hurtTime == 0) {
                var motion = mob.getDeltaMovement();
                mob.setDeltaMovement(0, motion.y, 0);
            }
            brain.setMemory(MemoryModuleType.LOOK_TARGET, eyes);
            var target = caller.getEyePosition();
            float yaw = (float) Math.toDegrees(Math.atan2(target.z - mob.getZ(), target.x - mob.getX())) - 90;
            mob.setYRot(Mth.approachDegrees(mob.getYRot(), yaw, 40));
            mob.yBodyRot = mob.getYRot();
            mob.getLookControl().setLookAt(target.x, target.y, target.z, 45F, 35F);
            mob.setYHeadRot(Mth.approachDegrees(mob.getYHeadRot(), yaw, 45));
        }

        private void walk(PokemonEntity mob, long now, BlockPos destination) {
            var brain = mob.getBrain();
            var nav = mob.getNavigation();
            brain.setMemory(MemoryModuleType.LOOK_TARGET, eyes);
            brain.setMemory(MemoryModuleType.WALK_TARGET, approach);
            if (now < nextPath) return;
            nextPath = now + 10;
            var current = nav.getPath();
            if (!nav.isDone() && current != null && current.getTarget().closerThan(destination, 2)) {
                assignedPath = current;
                return;
            }
            // The species' native navigator chooses a route; no repositioning or forced movement.
            var path = nav.createPath(caller, 1);
            if (path != null && path.getNodeCount() > 0 && nav.moveTo(path, WALK_SPEED)) assignedPath = nav.getPath();
        }

        private void release(PokemonEntity mob) {
            if (stage == Stage.STAY) VisitInteractions.status(mob, caller, false);
            var brain = mob.getBrain();
            var walk = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            boolean ownsMemory = walk == approach || (stand != null && walk == stand);
            if (ownsMemory) brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            if (brain.getMemory(MemoryModuleType.LOOK_TARGET).orElse(null) == eyes)
                brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
            // Leave a replacement combat/capture route alone, even if it points at the caller.
            var nav = mob.getNavigation();
            if (assignedPath != null && nav.getPath() == assignedPath && (ownsMemory || walk == null)) nav.stop();
        }
    }
}

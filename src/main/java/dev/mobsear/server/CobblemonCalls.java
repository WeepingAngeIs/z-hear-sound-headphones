package dev.mobsear.server;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Lives exclusively on the logical server and is loaded only when the optional mod is present. */
final class CobblemonCalls {
    private static final int LOOK_TICKS = 20;
    private static final int WALK_TICKS = 40;
    private static final int COOLDOWN_TICKS = 100;
    private static final double WALK_BLOCKS = 4;
    private static final Map<PokemonEntity, Response> responses = new IdentityHashMap<>();
    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private CobblemonCalls() {}

    static boolean isPokemon(Mob mob) { return mob instanceof PokemonEntity; }

    static void register() {
        NeoForge.EVENT_BUS.addListener(CobblemonCalls::tick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            responses.clear();
            cooldowns.clear();
        });
    }

    static boolean isReacting(Mob mob) {
        return responses.containsKey(mob);
    }

    static boolean hasResponsePath(Mob mob) {
        Response response = responses.get(mob);
        return response != null && response.walk != null;
    }

    static boolean hear(ServerPlayer speaker, ResourceLocation id, double radius, ToIntFunction<Mob> roll) {
        var species = PokemonSpecies.getByIdentifier(id);
        if (species == null || !id.equals(species.getResourceIdentifier())) return false;
        var level = speaker.serverLevel();
        long now = level.getServer().overworld().getGameTime();
        double limit = radius * radius;
        var area = speaker.getBoundingBox().inflate(radius);
        boolean heard = false;
        for (PokemonEntity mob : level.getEntitiesOfClass(PokemonEntity.class, area, mob ->
                mob.distanceToSqr(speaker) <= limit && id.equals(mob.getPokemon().getSpecies().getResourceIdentifier())
                        && available(mob))) {
            if (now < cooldowns.getOrDefault(mob.getUUID(), 0L)) continue;
            cooldowns.put(mob.getUUID(), now + COOLDOWN_TICKS);
            Vec3 sound = speaker.getEyePosition();
            var tracker = new BlockPosTracker(sound);
            mob.getBrain().setMemoryWithExpiry(MemoryModuleType.LOOK_TARGET, tracker, LOOK_TICKS);
            Approach walk = roll.applyAsInt(mob) == 0 ? startWalk(mob, sound) : null;
            var response = new Response(speaker, limit, mob.position(), sound, tracker, walk, now, mob.getYHeadRot(), mob.yBodyRot);
            responses.put(mob, response);
            orient(mob, response, now);
            heard = true;
        }
        return heard;
    }

    private static boolean available(PokemonEntity mob) {
        var pokemon = mob.getPokemon();
        var brain = mob.getBrain();
        return mob.isAlive() && !mob.isRemoved() && !mob.isNoAi() && !mob.isSleeping()
                && !mob.isPassenger() && !mob.isVehicle() && !mob.isLeashed()
                && pokemon.isWild() && !mob.isTame() && mob.getOwnerUUID() == null
                && pokemon.getOwnerUUID() == null && mob.getTethering() == null && pokemon.getTetheringId() == null
                && !pokemon.isFainted() && !mob.isBusy() && !mob.isBattling() && !mob.isBattleClone()
                && !mob.isEvolving() && mob.getBeamMode() == 0 && mob.getPhasingTargetId() == -1 && !mob.getQueuedToDespawn()
                && mob.getTarget() == null && mob.getLastHurtByMob() == null
                && brain.getActiveNonCoreActivity().orElse(Activity.IDLE) == Activity.IDLE
                && !brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && !brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                && !brain.hasMemoryValue(MemoryModuleType.IS_PANICKING)
                && !brain.hasMemoryValue(MemoryModuleType.BREED_TARGET);
    }

    private static Approach startWalk(PokemonEntity mob, Vec3 sound) {
        var nav = mob.getNavigation();
        if (!mob.canWalk() || mob.isFlying() || !mob.onGround() || mob.isInWaterOrBubble() || mob.isInLava()
                || !nav.isDone() || !mob.getBrain().checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT)) return null;
        var from = mob.position();
        var toward = new Vec3(sound.x - from.x, 0, sound.z - from.z);
        double distance = toward.length();
        if (distance < 3.5) return null;
        // Pick a fixed point in front of the Pokémon, leaving room around a nearby caller.
        Vec3 end = from.add(toward.scale(Math.min(WALK_BLOCKS, distance - 2.5) / distance));
        BlockPos block = BlockPos.containing(end);
        if (!mob.level().hasChunkAt(block) || !mob.level().getWorldBorder().isWithinBounds(block)
                || !mob.isWithinRestriction(block)) return null;
        Path path = nav.createPath(block, 0);
        if (!safeRoute(mob, path, from) || !nav.moveTo(path, 0.8)) return null;
        var memory = new WalkTarget(block, 0.8F, 0);
        mob.getBrain().setMemoryWithExpiry(MemoryModuleType.WALK_TARGET, memory, WALK_TICKS);
        return new Approach(nav.getPath(), memory);
    }

    private static boolean safeRoute(PokemonEntity mob, Path path, Vec3 from) {
        if (path == null || !path.canReach() || path.getNodeCount() > 8) return false;
        // Keep only a short ground route. The Pokémon's own navigator handles obstacles and collision.
        for (int n = 0; n < path.getNodeCount(); n++) {
            BlockPos node = path.getNodePos(n);
            if ((path.getNode(n).type == PathType.OPEN && mob.canFly()) || Math.abs(node.getY() - from.y) > 1.25
                    || from.distanceToSqr(Vec3.atBottomCenterOf(node)) > 25
                    || !mob.level().hasChunkAt(node) || !mob.level().getWorldBorder().isWithinBounds(node)
                    || !mob.isWithinRestriction(node) || !mob.level().getFluidState(node).isEmpty()
                    || !mob.level().getBlockState(node.below()).isFaceSturdy(mob.level(), node.below(), Direction.UP)) {
                return false;
            }
        }
        return true;
    }

    private static void orient(PokemonEntity mob, Response r, long now) {
        float yaw = (float)Math.toDegrees(Math.atan2(r.sound.z - mob.getZ(), r.sound.x - mob.getX())) - 90;
        boolean ownMovement = mob.getNavigation().isDone() || (r.walk != null && r.walk.ownsPath(mob));
        if (ownMovement) {
            mob.setYRot(Mth.approachDegrees(mob.getYRot(), yaw, 30));
        }
        // Navigation can clamp the native head controller back toward the path on every tick.
        // Turn the rendered upper body and head while leaving an existing walk's direction intact.
        float ticks = (float) (now - r.start + 1);
        mob.yBodyRot = Mth.approachDegrees(r.bodyYaw, yaw, 30 * ticks);
        mob.getLookControl().setLookAt(r.sound.x, r.sound.y, r.sound.z, 40F, 35F);
        mob.setYHeadRot(Mth.approachDegrees(r.headYaw, yaw, 40 * ticks));
    }

    private static void tick(ServerTickEvent.Post event) {
        long now = event.getServer().overworld().getGameTime();
        if (now % 20 == 0) cooldowns.values().removeIf(expiry -> expiry <= now);
        responses.entrySet().removeIf(entry -> {
            PokemonEntity mob = entry.getKey();
            Response r = entry.getValue();
            ServerPlayer speaker = r.speaker;
            boolean allowed = speaker.isAlive() && !speaker.isRemoved() && !speaker.isCreative() && !speaker.isSpectator()
                    && mob.level() == speaker.level() && mob.distanceToSqr(speaker) <= r.limit && available(mob);
            boolean looking = allowed && now < r.start + LOOK_TICKS;
            if (looking) orient(mob, r, now);
            else if (mob.getBrain().checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)
                    && mob.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null) == r.tracker) {
                mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            }
            boolean walking = false;
            if (r.walk != null) {
                var nav = mob.getNavigation();
                boolean ownMemory = r.walk.ownsMemory(mob);
                boolean ownPath = r.walk.ownsPath(mob);
                walking = allowed && ownMemory && ownPath && !nav.isDone() && now < r.start + WALK_TICKS
                        && mob.position().distanceToSqr(r.from) < WALK_BLOCKS * WALK_BLOCKS
                        && (nav.getPath() == r.walk.original || safeRoute(mob, nav.getPath(), r.from));
                if (!walking) {
                    if (ownMemory) mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    if (ownPath && !nav.isDone()) nav.stop();
                    if (looking) entry.setValue(r.withoutWalk());
                }
            }
            return !looking && !walking;
        });
    }

    private record Approach(Path original, WalkTarget memory) {
        boolean ownsMemory(PokemonEntity mob) {
            return mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == memory;
        }

        boolean ownsPath(PokemonEntity mob) {
            var path = mob.getNavigation().getPath();
            var active = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            // The native brain can recalculate our short route. A new native walk keeps its own path.
            if (path == null || (active != null && active != memory)) return false;
            return path == original || (active == memory && path.getTarget().equals(original.getTarget()));
        }
    }

    private record Response(ServerPlayer speaker, double limit, Vec3 from, Vec3 sound, BlockPosTracker tracker,
                            Approach walk, long start, float headYaw, float bodyYaw) {
        Response withoutWalk() {
            return new Response(speaker, limit, from, sound, tracker, null, start, headYaw, bodyYaw);
        }
    }
}

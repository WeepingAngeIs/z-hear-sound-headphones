package dev.mobsear.server;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.level.pathfinder.Path;
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

    static void hear(ServerPlayer speaker, ResourceLocation id, double radius, ToIntFunction<Mob> roll) {
        var species = PokemonSpecies.getByIdentifier(id);
        if (species == null || !id.equals(species.getResourceIdentifier())) return;
        var level = speaker.serverLevel();
        long now = level.getServer().overworld().getGameTime();
        double limit = radius * radius;
        var area = speaker.getBoundingBox().inflate(radius);
        for (PokemonEntity mob : level.getEntitiesOfClass(PokemonEntity.class, area, mob ->
                mob.distanceToSqr(speaker) <= limit && mob.getPokemon().getSpecies() == species && available(mob))) {
            if (now < cooldowns.getOrDefault(mob.getUUID(), 0L)) continue;
            cooldowns.put(mob.getUUID(), now + COOLDOWN_TICKS);
            Vec3 sound = speaker.getEyePosition();
            var tracker = new BlockPosTracker(sound);
            mob.getBrain().setMemoryWithExpiry(MemoryModuleType.LOOK_TARGET, tracker, LOOK_TICKS);
            Path path = roll.applyAsInt(mob) == 0 ? startWalk(mob, sound) : null;
            var response = new Response(speaker, limit, mob.position(), sound, tracker, path, now);
            responses.put(mob, response);
            orient(mob, response);
        }
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
                && !brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && !brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                && !brain.hasMemoryValue(MemoryModuleType.IS_PANICKING)
                && !brain.hasMemoryValue(MemoryModuleType.BREED_TARGET);
    }

    private static Path startWalk(PokemonEntity mob, Vec3 sound) {
        var nav = mob.getNavigation();
        if (!mob.canWalk() || !mob.onGround() || mob.isInWaterOrBubble() || mob.isInLava()
                || !nav.isDone() || mob.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) return null;
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
        if (path == null || !path.canReach() || path.getNodeCount() > 8) return null;
        // Keep only a short ground route. The Pokémon's own navigator handles obstacles and collision.
        for (int n = 0; n < path.getNodeCount(); n++) {
            BlockPos node = path.getNodePos(n);
            if (Math.abs(node.getY() - from.y) > 1.25 || from.distanceToSqr(Vec3.atBottomCenterOf(node)) > 25
                    || !mob.level().hasChunkAt(node) || !mob.level().getWorldBorder().isWithinBounds(node)
                    || !mob.isWithinRestriction(node) || !mob.level().getFluidState(node).isEmpty()
                    || !mob.level().getBlockState(node.below()).isFaceSturdy(mob.level(), node.below(), net.minecraft.core.Direction.UP)) {
                return null;
            }
        }
        return nav.moveTo(path, 0.8) ? nav.getPath() : null;
    }

    private static void orient(PokemonEntity mob, Response r) {
        // Cobblemon's idle Brain can look around every tick. Ask its ordinary look controller for
        // this brief glance and turn the body only while stationary or following our own short path.
        if (mob.getNavigation().isDone() || (r.path != null && mob.getNavigation().getPath() == r.path)) {
            float yaw = (float)Math.toDegrees(Math.atan2(r.sound.z - mob.getZ(), r.sound.x - mob.getX())) - 90;
            mob.setYRot(Mth.approachDegrees(mob.getYRot(), yaw, 30));
            mob.yBodyRot = Mth.approachDegrees(mob.yBodyRot, yaw, 30);
        }
        mob.getLookControl().setLookAt(r.sound.x, r.sound.y, r.sound.z, 40F, 35F);
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
            if (looking) orient(mob, r);
            else if (mob.getBrain().checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)
                    && mob.getBrain().getMemory(MemoryModuleType.LOOK_TARGET).orElse(null) == r.tracker) {
                mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            }
            boolean ownPath = r.path != null && mob.getNavigation().getPath() == r.path && !mob.getNavigation().isDone();
            boolean walking = allowed && ownPath && now < r.start + WALK_TICKS
                    && mob.position().distanceToSqr(r.from) < WALK_BLOCKS * WALK_BLOCKS
                    && !mob.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET);
            if (ownPath && !walking) mob.getNavigation().stop();
            return !looking && !walking;
        });
    }

    private record Response(ServerPlayer speaker, double limit, Vec3 from, Vec3 sound, BlockPosTracker tracker,
                            Path path, long start) {}
}

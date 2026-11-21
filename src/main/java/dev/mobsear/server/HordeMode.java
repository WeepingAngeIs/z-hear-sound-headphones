package dev.mobsear.server;

import dev.mobsear.net.VoiceSignal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

/** Server-thread state. Accepted PCM is measured in place and never retained. */
public final class HordeMode {
    public record Rules(boolean enabled, int cooldownTicks, int groupCap) {}
    public static final HordeMode LIVE = new HordeMode();
    private static final String TEMPORARY = "mobsear_temporary_horde";
    private static final String OWNER = "mobsear_horde_owner";
    private static final int SERVER_CAP = 24;
    private static final int LIFETIME = 400;
    private final Map<ServerPlayer, Encounter> opted = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    private static final class Encounter {
        final List<Zombie> group = new ArrayList<>();
        long firstLoud, lastLoud, expires;
        int frames;
    }

    public boolean optedIn(ServerPlayer player) { return opted.containsKey(player); }

    public boolean consent(ServerPlayer player, boolean requested, Rules rules) {
        if (requested && rules.enabled && eligible(player)) opted.computeIfAbsent(player, key -> new Encounter());
        else forget(player);
        return optedIn(player);
    }

    static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isCreative() && !player.isSpectator()
                && player.level().getDifficulty() != Difficulty.PEACEFUL;
    }

    public void heard(ServerPlayer speaker, byte[] pcm, double radius, Rules rules, long tick) {
        Encounter encounter = opted.get(speaker);
        if (encounter == null || !rules.enabled || !eligible(speaker) || radius <= 0
                || !encounter.group.isEmpty() || tick < cooldown.getOrDefault(speaker.getUUID(), 0L)
                || VoiceSignal.rms(pcm) < 0.03) return;

        if (encounter.frames == 0 || tick - encounter.lastLoud > 6) {
            encounter.firstLoud = tick;
            encounter.frames = 0;
        }
        encounter.lastLoud = tick;
        if (++encounter.frames < 60 || tick - encounter.firstLoud < 24) return;
        encounter.frames = 0;
        cooldown.put(speaker.getUUID(), tick + rules.cooldownTicks);
        int total = opted.values().stream().mapToInt(active -> active.group.size()).sum();
        int cap = Math.min(SERVER_CAP - total, Math.min(rules.groupCap, speaker.level().getDifficulty().getId() + 1));
        if (cap <= 0) return;

        var level = speaker.serverLevel();
        var zombies = level.getEntitiesOfClass(Zombie.class, speaker.getBoundingBox().inflate(radius),
                mob -> !(mob instanceof ZombifiedPiglin) && !temporary(mob) && mob.isAlive() && !mob.isNoAi()
                        && mob.getTarget() == null && mob.canAttack(speaker) && !mob.isAlliedTo(speaker)
                        && mob.isWithinRestriction(speaker.blockPosition()) && mob.distanceToSqr(speaker) <= radius * radius);
        zombies.sort(Comparator.comparingDouble(mob -> mob.distanceToSqr(speaker)));
        for (var zombie : zombies) {
            zombie.setTarget(speaker);
            if (zombie.getTarget() == speaker) encounter.group.add(zombie);
            if (encounter.group.size() == cap) break;
        }
        // A fixed search budget avoids loading or scanning distant chunks when a cave is full.
        int reach = (int) Math.min(12, radius);
        if (reach >= 5 && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            for (int tries = 0; tries < 24 && encounter.group.size() < cap; tries++) {
                BlockPos pos = speaker.blockPosition().offset(level.random.nextIntBetweenInclusive(-reach, reach),
                        tries % 3 == 0 ? 0 : level.random.nextIntBetweenInclusive(-2, 2),
                        level.random.nextIntBetweenInclusive(-reach, reach));
                trySpawnAt(speaker, pos, radius);
            }
        }
        encounter.expires = tick + LIFETIME;
    }

    Zombie trySpawnAt(ServerPlayer speaker, BlockPos feet, double radius) {
        Encounter encounter = opted.get(speaker);
        if (encounter == null || !eligible(speaker)) return null;
        var level = speaker.serverLevel();
        Vec3 position = Vec3.atBottomCenterOf(feet);
        double distance = speaker.distanceToSqr(position);
        if (distance < 25 || distance > radius * radius || !level.getWorldBorder().isWithinBounds(feet)
                || level.isOutsideBuildHeight(feet.below()) || level.isOutsideBuildHeight(feet.above())
                || !level.hasChunksAt(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))
                || (level.isDay() && level.canSeeSky(feet))
                || !SpawnPlacements.isSpawnPositionOk(EntityType.ZOMBIE, level, feet)
                || !SpawnPlacements.checkSpawnRules(EntityType.ZOMBIE, level, MobSpawnType.NATURAL, feet, level.random)
                || level.players().stream().anyMatch(p -> p != speaker && p.distanceToSqr(position) < 36)) return null;
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) return null;
        zombie.moveTo(position.x, position.y, position.z, level.random.nextFloat() * 360, 0);
        if (!level.noCollision(zombie) || !level.isUnobstructed(zombie)
                || !level.getEntities(zombie, zombie.getBoundingBox(), Entity::isAlive).isEmpty()) return null;
        zombie.addTag(TEMPORARY);
        zombie.getPersistentData().putUUID(OWNER, speaker.getUUID());
        zombie.setCanPickUpLoot(false);
        zombie.setCanBreakDoors(false);
        zombie.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(0);
        zombie.setTarget(speaker);
        if (zombie.getTarget() != speaker || !level.addFreshEntity(zombie)) return null;
        encounter.group.add(zombie);
        return zombie;
    }

    public void tick(Rules rules, double radius, long tick, Consumer<ServerPlayer> revoked) {
        cooldown.values().removeIf(until -> tick >= until);
        var iterator = opted.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            ServerPlayer owner = item.getKey();
            var encounter = item.getValue();
            boolean endConsent = !rules.enabled || !eligible(owner);
            encounter.group.removeIf(mob -> {
                if (endConsent || tick >= encounter.expires || !mob.isAlive() || mob.level() != owner.level()
                        || owner.distanceToSqr(mob) > radius * radius) {
                    release(mob, owner);
                    return true;
                }
                return false;
            });
            if (endConsent) {
                iterator.remove();
                revoked.accept(owner);
            }
        }
    }

    public void forget(ServerPlayer player) {
        Encounter encounter = opted.remove(player);
        if (encounter != null) encounter.group.forEach(mob -> release(mob, player));
    }

    public void clear() {
        List.copyOf(opted.keySet()).forEach(this::forget);
        cooldown.clear();
    }

    private static void release(Zombie zombie, ServerPlayer speaker) {
        if (temporary(zombie)) zombie.discard();
        else if (zombie.getTarget() == speaker) {
            zombie.setTarget(null);
            zombie.getNavigation().stop();
        }
    }

    static boolean temporary(Entity entity) { return entity instanceof Zombie && entity.getTags().contains(TEMPORARY); }

    public static void restrictTarget(LivingChangeTargetEvent event) {
        var target = event.getNewAboutToBeSetTarget();
        var entity = event.getEntity();
        if (target != null && temporary(entity) && (!entity.getPersistentData().hasUUID(OWNER)
                || !entity.getPersistentData().getUUID(OWNER).equals(target.getUUID()))) {
            event.setCanceled(true);
        }
    }

    public static void loaded(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && !event.getLevel().isClientSide() && temporary(event.getEntity())) event.setCanceled(true);
    }
    public static void drops(LivingDropsEvent event) { if (temporary(event.getEntity())) event.setCanceled(true); }
    public static void experience(LivingExperienceDropEvent event) { if (temporary(event.getEntity())) event.setCanceled(true); }
    public static void convert(LivingConversionEvent.Pre event) { if (temporary(event.getEntity())) event.setCanceled(true); }
    public static void travel(EntityTravelToDimensionEvent event) { if (temporary(event.getEntity())) event.setCanceled(true); }
}

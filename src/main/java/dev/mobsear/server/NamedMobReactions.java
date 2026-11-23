package dev.mobsear.server;

import dev.mobsear.net.MobCallTypes;
import dev.mobsear.net.Packets;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent.LivingTargetType;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Voice can draw an ordinary hostile's attack target. Its own AI controls pursuit and attacks. */
public final class NamedMobReactions {
    private static final int LOOK_TICKS = 20, COOLDOWN_TICKS = 60;
    private static final int POSE_INTERVAL = 5;
    private static final Map<Mob, Glance> active = new IdentityHashMap<>();
    private static final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final ResourceLocation HEARD_RANGE = ResourceLocation.fromNamespaceAndPath("mobsear", "heard_target_range");
    private static final Map<Mob, Pursuit> distantTargets = new IdentityHashMap<>();

    private NamedMobReactions() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(NamedMobReactions::afterLevelTick);
        NeoForge.EVENT_BUS.addListener(NamedMobReactions::expireCooldowns);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            distantTargets.keySet().forEach(NamedMobReactions::restoreRange);
            distantTargets.clear();
            active.clear();
            cooldowns.clear();
        });
    }

    static boolean isReacting(Mob mob) { return active.containsKey(mob); }

    static ResourceLocation hear(ServerPlayer caller, ResourceLocation id, double radius) {
        if (!eligibleCaller(caller) || !Double.isFinite(radius) || radius <= 0) return null;
        boolean dungeonMimic = MobCallTypes.DUNGEON_MIMIC.equals(id) && ModList.get().isLoaded("cumimic");
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (!dungeonMimic && (type == null || !MobCallTypes.eligibleType(id, type))) return null;
        var level = caller.serverLevel();
        double limit = radius * radius;
        Mob nearest = null;
        for (var mob : level.getEntitiesOfClass(Mob.class, caller.getBoundingBox().inflate(radius), candidate ->
                candidate.distanceToSqr(caller) <= limit && (dungeonMimic ? candidate.getTags().contains("cu_mimic") : candidate.getType() == type))) {
            if (respond(caller, mob, limit)
                    && (nearest == null || mob.distanceToSqr(caller) < nearest.distanceToSqr(caller))) {
                nearest = mob;
            }
        }
        return nearest == null ? null : listenerName(nearest);
    }

    static ResourceLocation listenerName(Mob mob) {
        if (mob.getTags().contains("cu_mimic") && ModList.get().isLoaded("cumimic")) return MobCallTypes.DUNGEON_MIMIC;
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
    }

    static boolean respond(ServerPlayer caller, Mob mob, double limit) {
        long now = caller.serverLevel().getServer().overworld().getGameTime();
        if (cooldowns.getOrDefault(mob.getUUID(), 0L) > now
                || mob instanceof Creeper creeper && (creeper.isIgnited() || creeper.getSwellDir() > 0)) return false;
        boolean aggro = canDrawAggro(caller, mob);
        if (aggro) {
            if (!targetCaller(mob, caller)) return false;
            followHeardTarget(mob, caller, limit, now);
            if (mob instanceof Zombie zombie) MobReactions.pauseZombie(zombie, caller.getEyePosition());
        } else if (!canGlance(mob)) return false;
        var glance = new Glance(caller, caller.getEyePosition(), limit, now, mob.getYHeadRot(), mob.yBodyRot, aggro);
        active.put(mob, glance);
        cooldowns.put(mob.getUUID(), now + (aggro ? 40 : COOLDOWN_TICKS));
        turn(mob, glance, now);
        return true;
    }

    private static boolean eligibleCaller(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.isCreative();
    }

    private static boolean canDrawAggro(ServerPlayer caller, Mob mob) {
        if (!MobReactions.hostile(mob) || !mob.isAlive() || mob.isRemoved() || mob.isNoAi() || mob.isSleeping()
                || mob.isInvulnerable() || mob.isPassenger() || mob.isVehicle() || mob.isAlliedTo(caller)
                || !mob.canAttack(caller) || !mob.isWithinRestriction(caller.blockPosition())
                || mob instanceof TamableAnimal tamed && tamed.isTame()) return false;
        if (mob instanceof EnderMan || mob instanceof Spider && mob.getTarget() == null) return false;
        // Encounter brains, disguises and the Warden's anger meter own their targeting.
        if (mob instanceof Warden || mob instanceof EnderDragon || mob instanceof WitherBoss || mob.getType().is(Tags.EntityTypes.BOSSES)
                || mob.getTags().contains("cu_mimic") || SCRIPT_IDLE.get(mob.getClass()) != ORDINARY_AI) return false;
        return !mob.getBrain().hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                && !mob.getBrain().hasMemoryValue(MemoryModuleType.IS_PANICKING);
    }

    private static boolean targetCaller(Mob mob, ServerPlayer caller) {
        var brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
            if (brain.isMemoryValue(MemoryModuleType.ATTACK_TARGET, caller)) return true;
            // Mirror vanilla StartAttacking's NeoForge event and respect cancellations and replacement targets.
            var event = CommonHooks.onLivingChangeTarget(mob, caller, LivingTargetType.BEHAVIOR_TARGET);
            var target = event.getNewAboutToBeSetTarget();
            if (event.isCanceled() || target == null) return false;
            brain.setMemory(MemoryModuleType.ATTACK_TARGET, target);
            brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            return target == caller;
        }
        if (mob.getTarget() != caller) mob.setTarget(caller);
        return mob.getTarget() == caller;
    }

    private static boolean targeting(Mob mob, ServerPlayer caller) {
        return mob.getTarget() == caller || mob.getBrain().isMemoryValue(MemoryModuleType.ATTACK_TARGET, caller);
    }

    // Vanilla's TargetGoal drops a legitimate target immediately beyond the mob's usual follow range.
    // Give only this pursuit time to close that gap. Native attacks, sight checks and retaliation still run.
    private static void followHeardTarget(Mob mob, ServerPlayer caller, double limit, long now) {
        if (distantTargets.remove(mob) != null) restoreRange(mob);
        var range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range == null || mob.getTarget() != caller) return;
        double ordinary = range.getValue();
        if (ordinary <= 0 || mob.distanceToSqr(caller) <= ordinary * ordinary) return;
        double factor = Math.sqrt(limit) / ordinary - 1;
        if (factor <= 0) return;
        range.addTransientModifier(new AttributeModifier(HEARD_RANGE, factor, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        distantTargets.put(mob, new Pursuit(caller, ordinary * ordinary, limit, now + 160));
    }

    private static void restoreRange(Mob mob) {
        var range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range != null) range.removeModifier(HEARD_RANGE);
    }

    private record Pursuit(ServerPlayer caller, double ordinaryLimit, double hearingLimit, long expires) {
        boolean ended(Mob mob, long now) {
            double distance = mob.distanceToSqr(caller);
            return mob.isRemoved() || !mob.isAlive() || mob.isNoAi() || !eligibleCaller(caller) || mob.level() != caller.level()
                    || mob.getTarget() != caller || now >= expires || distance < ordinaryLimit || distance > hearingLimit;
        }
    }

    private static boolean canGlance(Mob mob) {
        if (!mob.isAlive() || mob.isRemoved() || mob.isNoAi() || mob.isSleeping() || mob.isInvulnerable()
                || mob.isAggressive() || mob.isPassenger() || mob.isVehicle() || mob.hurtTime > 0
                || mob.getTarget() != null || mob.getLastHurtByMob() != null || PokemonReactions.isPokemon(mob)) return false;
        var brain = mob.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET) || brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                || brain.hasMemoryValue(MemoryModuleType.IS_PANICKING) || brain.hasMemoryValue(MemoryModuleType.BREED_TARGET)) return false;
        if (mob instanceof NeutralMob neutral && neutral.isAngry()) return false;
        if (mob instanceof EnderMan ender && (ender.isCreepy() || ender.hasBeenStaredAt())) return false;
        if (mob instanceof WitherBoss wither && (wither.getInvulnerableTicks() > 0 || wither.getAlternativeTarget(0) != 0
                || wither.getAlternativeTarget(1) != 0 || wither.getAlternativeTarget(2) != 0)) return false;
        if (mob instanceof EnderDragon dragon && dragon.getPhaseManager().getCurrentPhase().getPhase() != EnderDragonPhase.HOVERING) return false;
        return SCRIPT_IDLE.get(mob.getClass()).test(mob);
    }

    // These optional pack entities expose encounter states independently of Minecraft's attack target.
    // Resolve their read-only API once, without linking the standalone jar against the dimension mod.
    private static final Predicate<Mob> ORDINARY_AI = mob -> true;
    private static final ClassValue<Predicate<Mob>> SCRIPT_IDLE = new ClassValue<>() {
        @Override protected Predicate<Mob> computeValue(Class<?> clazz) {
            return switch (clazz.getName()) {
                case "dev.uwu.dimension.BlossomWarden" -> idleWhenClear(clazz, "offender", "chasing", "gripping", "throwing");
                case "dev.uwu.dimension.VelvetVesper" -> {
                    var phaseIdle = idleWhenClear(clazz, "guarding", "folding", "casting");
                    Method validVictim = courtTargetCheck(clazz);
                    // The Mirror Court can aim at a participant even during an open phase,
                    // and does not expose that focus through Mob.getTarget().
                    yield mob -> phaseIdle.test(mob) && mob.level().players().stream().noneMatch(player ->
                            player.isAlive() && !player.isCreative() && !player.isSpectator()
                                    && mob.distanceToSqr(player) < 400 && Math.abs(player.getY() - mob.getY()) < 9
                                    && player instanceof ServerPlayer target && courtCanAim(mob, target, validVictim));
                }
                default -> ORDINARY_AI;
            };
        }
    };

    private static Method courtTargetCheck(Class<?> clazz) {
        try {
            var method = clazz.getDeclaredMethod("validVictim", ServerPlayer.class);
            return method.trySetAccessible() ? method : null;
        } catch (ReflectiveOperationException | SecurityException unavailable) { return null; }
    }

    private static boolean courtCanAim(Mob mob, ServerPlayer target, Method check) {
        if (check != null) {
            try { return Boolean.TRUE.equals(check.invoke(mob, target)); }
            catch (ReflectiveOperationException unavailable) { /* Use the conservative line-of-sight check below. */ }
        }
        return mob.hasLineOfSight(target);
    }

    private static Predicate<Mob> idleWhenClear(Class<?> clazz, String... names) {
        Method[] methods = new Method[names.length];
        try {
            for (int i = 0; i < names.length; i++) methods[i] = clazz.getMethod(names[i]);
        } catch (NoSuchMethodException incompatible) { return mob -> false; }
        return mob -> {
            try {
                for (Method method : methods) {
                    Object state = method.invoke(mob);
                    if (state != null && !Boolean.FALSE.equals(state)) return false;
                }
                return true;
            } catch (ReflectiveOperationException unavailable) { return false; }
        };
    }

    private static void expireCooldowns(ServerTickEvent.Post event) {
        long now = event.getServer().overworld().getGameTime();
        if (now % 20 == 0) cooldowns.values().removeIf(expiry -> expiry <= now);
    }

    // Apply the pose immediately after this world's native AI has updated its head and body.
    private static void afterLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || active.isEmpty() && distantTargets.isEmpty()) return;
        long now = level.getServer().overworld().getGameTime();
        distantTargets.entrySet().removeIf(entry -> {
            var mob = entry.getKey();
            if (mob.level() != level && !mob.isRemoved()) return false;
            if (!entry.getValue().ended(mob, now)) return false;
            restoreRange(mob);
            return true;
        });
        active.entrySet().removeIf(entry -> {
            var mob = entry.getKey();
            var glance = entry.getValue();
            if (mob.isRemoved()) return true;
            if (mob.level() != level) return false;
            if (now >= glance.start + LOOK_TICKS || !eligibleCaller(glance.caller) || mob.level() != glance.caller.level()
                    || mob.distanceToSqr(glance.caller) > glance.limit
                    || (glance.aggro ? !mob.isAlive() || mob.isNoAi() || !targeting(mob,glance.caller) : !canGlance(mob))) {
                syncPose(mob, level, 0, 0);
                return true;
            }
            float yaw = turn(mob, glance, now);
            int elapsed = (int) (now - glance.start);
            if (elapsed % POSE_INTERVAL == 0) {
                syncPose(mob, level, yaw, Math.min(Packets.MobPose.MAX_TICKS, LOOK_TICKS - elapsed));
            }
            return false;
        });
    }

    private static void syncPose(Mob mob, ServerLevel level, float yaw, int ticks) {
        if (mob.getTags().contains("cu_mimic")) return;
        var pose = new Packets.MobPose(mob.getId(), (byte) Mth.floor(yaw * 256F / 360F), ticks);
        for (var viewer : level.getChunkSource().chunkMap.getPlayersWatching(mob)) {
            if (viewer.connection.hasChannel(Packets.MobPose.TYPE)) viewer.connection.send(pose);
        }
    }

    private static float turn(Mob mob, Glance glance, long now) {
        Vec3 sound = glance.aggro ? glance.caller.getEyePosition() : glance.sound;
        float yaw = (float) Math.toDegrees(Math.atan2(sound.z - mob.getZ(), sound.x - mob.getX())) - 90;
        float ticks = (float) (now - glance.start + 1);
        mob.getLookControl().setLookAt(sound.x, sound.y, sound.z, 40, 35);
        mob.setYHeadRot(Mth.approachDegrees(glance.head, yaw, 40 * ticks));
        mob.yBodyRot = Mth.approachDegrees(glance.body, yaw, 30 * ticks);
        return yaw;
    }

    private record Glance(ServerPlayer caller, Vec3 sound, double limit, long start, float head, float body, boolean aggro) {}
}

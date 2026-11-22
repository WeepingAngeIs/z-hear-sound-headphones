package dev.mobsear.server;

import dev.mobsear.net.MobCallTypes;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Exact entity-type calls give a brief visual turn. Target, navigation and physical heading belong to the mob. */
public final class NamedMobReactions {
    private static final int LOOK_TICKS = 20, COOLDOWN_TICKS = 60;
    private static final Map<Mob, Glance> active = new IdentityHashMap<>();
    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private NamedMobReactions() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(NamedMobReactions::tick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> { active.clear(); cooldowns.clear(); });
    }

    static boolean isReacting(Mob mob) { return active.containsKey(mob); }

    static void hear(ServerPlayer caller, ResourceLocation id, double radius) {
        if (!eligibleCaller(caller) || !Double.isFinite(radius) || radius <= 0) return;
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null || !MobCallTypes.eligibleType(id, type)) return;
        var level = caller.serverLevel();
        double limit = radius * radius;
        for (var mob : level.getEntitiesOfClass(Mob.class, caller.getBoundingBox().inflate(radius), candidate ->
                candidate.getType() == type && candidate.distanceToSqr(caller) <= limit)) {
            glanceIfCalm(caller, mob, limit);
        }
    }

    static void glanceIfCalm(ServerPlayer caller, Mob mob, double limit) {
        long now = caller.serverLevel().getServer().overworld().getGameTime();
        if (cooldowns.getOrDefault(mob.getUUID(), 0L) > now || !canGlance(mob)) return;
        var glance = new Glance(caller, caller.getEyePosition(), limit, now, mob.getYHeadRot(), mob.yBodyRot);
        active.put(mob, glance);
        cooldowns.put(mob.getUUID(), now + COOLDOWN_TICKS);
        turn(mob, glance, now);
    }

    private static boolean eligibleCaller(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator() && !player.isCreative();
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
    private static final ClassValue<Predicate<Mob>> SCRIPT_IDLE = new ClassValue<>() {
        @Override protected Predicate<Mob> computeValue(Class<?> clazz) {
            return switch (clazz.getName()) {
                case "dev.uwu.dimension.BlossomWarden" -> idleWhenClear(clazz, "offender", "chasing", "gripping", "throwing");
                case "dev.uwu.dimension.VelvetVesper" -> {
                    var phaseIdle = idleWhenClear(clazz, "guarding", "folding", "casting");
                    // The Mirror Court can aim at a participant even during an open phase,
                    // and does not expose that focus through Mob.getTarget().
                    yield mob -> phaseIdle.test(mob) && mob.level().players().stream().noneMatch(player ->
                            player.isAlive() && !player.isCreative() && !player.isSpectator()
                                    && mob.distanceToSqr(player) < 400 && Math.abs(player.getY() - mob.getY()) < 9
                                    && mob.hasLineOfSight(player));
                }
                default -> mob -> true;
            };
        }
    };

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

    private static void tick(ServerTickEvent.Post event) {
        long now = event.getServer().overworld().getGameTime();
        if (now % 20 == 0) cooldowns.values().removeIf(expiry -> expiry <= now);
        active.entrySet().removeIf(entry -> {
            var mob = entry.getKey();
            var glance = entry.getValue();
            if (now >= glance.start + LOOK_TICKS || !eligibleCaller(glance.caller) || mob.level() != glance.caller.level()
                    || mob.distanceToSqr(glance.caller) > glance.limit || !canGlance(mob)) return true;
            turn(mob, glance, now);
            return false;
        });
    }

    private static void turn(Mob mob, Glance glance, long now) {
        Vec3 sound = glance.sound;
        float yaw = (float) Math.toDegrees(Math.atan2(sound.z - mob.getZ(), sound.x - mob.getX())) - 90;
        float ticks = (float) (now - glance.start + 1);
        mob.getLookControl().setLookAt(sound.x, sound.y, sound.z, 40, 35);
        mob.setYHeadRot(Mth.approachDegrees(glance.head, yaw, 40 * ticks));
        mob.yBodyRot = Mth.approachDegrees(glance.body, yaw, 30 * ticks);
    }

    private record Glance(ServerPlayer caller, Vec3 sound, double limit, long start, float head, float body) {}
}

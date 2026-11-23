package dev.mobsear.packprobe;

import dev.mobsear.net.Cue;
import dev.mobsear.server.MobReactions;
import dev.mobsear.server.NamedMobReactions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.ModList;

/** Isolates server targeting from speech in a disposable world with the real pack entities loaded. */
final class PackHostileTargets {
    static void verify(ServerPlayer caller, boolean fullPack) throws ReflectiveOperationException {
        var subjects = new ArrayList<Mob>();
        var id = ResourceLocation.parse(fullPack ? "twilightforest:kobold" : "minecraft:pillager");
        require(!fullPack || ModList.get().isLoaded("twilightforest"), "Twilight Forest missing from pack");
        var modded = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElseThrow();
        try {
            var original = spawn(caller, EntityType.IRON_GOLEM, 4, subjects);
            original.setNoAi(true);
            var skeletonA = spawn(caller, EntityType.SKELETON, 10, subjects);
            var skeletonB = spawn(caller, EntityType.SKELETON, 12, subjects);
            var zombie = spawn(caller, EntityType.ZOMBIE, 16, subjects);
            var nearby = spawn(caller, modded, 14, subjects);
            var outside = spawn(caller, modded, 35, subjects);
            for (var mob : List.of(skeletonA, skeletonB, zombie, nearby, outside)) {
                mob.setTarget(original);
                require(mob.getTarget() == original, "Failed to set up original target for " + mob.getType());
            }
            MobReactions.handle(caller, Cue.SKELETON, 32);
            require(skeletonA.getTarget() == caller && skeletonB.getTarget() == caller &&
                    zombie.getTarget() == original && nearby.getTarget() == original && outside.getTarget() == original,
                    "Named group selection in the mixed crowd");
            MobReactions.handle(caller, Cue.MOBS, 32);
            require(skeletonA.getTarget() == caller && skeletonB.getTarget() == caller &&
                    zombie.getTarget() == caller && nearby.getTarget() == caller && outside.getTarget() == original,
                    "Broad group selection or radius");

            var named = spawn(caller, modded, 18, subjects);
            named.setTarget(original);
            require(named.getTarget() == original, "Initial target for exact type call");
            var hear = NamedMobReactions.class.getDeclaredMethod("hear", ServerPlayer.class, ResourceLocation.class, double.class);
            hear.setAccessible(true);
            require(id.equals(hear.invoke(null, caller, id, 32.0)) && named.getTarget() == caller && outside.getTarget() == original,
                    "Registered hostile name or radius");
            System.out.println("ACOUSTIC_HOSTILE_TARGETS_PASS registered=" + id +
                    " namedSkeletons=2 untouchedZombie=true broadTargets=4 exactNameAt18=true outAt35=true");
        } finally {
            subjects.forEach(Mob::discard);
        }
    }

    private static Mob spawn(ServerPlayer caller, EntityType<?> type, int distance, List<Mob> subjects) {
        var level = caller.serverLevel();
        if (!(type.create(level) instanceof Mob mob)) throw new IllegalStateException("Missing mob " + type);
        mob.moveTo(caller.getX() + distance, caller.getY(), caller.getZ(), 90, 0);
        mob.setPersistenceRequired();
        require(level.addFreshEntity(mob), "Could not spawn " + type);
        subjects.add(mob);
        return mob;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}

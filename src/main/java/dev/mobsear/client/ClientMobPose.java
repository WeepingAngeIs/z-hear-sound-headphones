package dev.mobsear.client;

import dev.mobsear.MobsEar;
import dev.mobsear.net.Packets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

final class ClientMobPose {
    private record Pose(float yaw, long expires) {}
    private static final IdentityHashMap<Mob, Pose> active = new IdentityHashMap<>();
    private static final ArrayList<Mob> expired = new ArrayList<>();

    private ClientMobPose() {}

    static void register() {
        MobsEar.mobPose = ClientMobPose::receive;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ClientMobPose::afterTick);
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel().isClientSide()) active.clear();
        });
    }

    private static void receive(Packets.MobPose packet) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(packet.entityId()) instanceof Mob mob)) return;
        if (packet.ticks() == 0) active.remove(mob);
        else active.put(mob, new Pose(packet.yaw() * 360F / 256F, level.getGameTime() + packet.ticks()));
    }

    private static void afterTick(ClientTickEvent.Post ignored) {
        if (active.isEmpty() || Minecraft.getInstance().isPaused()) return;
        active.forEach(ClientMobPose::apply);
        for (int i = 0; i < expired.size(); i++) active.remove(expired.get(i));
        expired.clear();
    }

    private static void apply(Mob mob, Pose pose) {
        if (mob.level() != Minecraft.getInstance().level || !mob.isAlive() || mob.isRemoved() || mob.level().getGameTime() >= pose.expires()) {
            expired.add(mob);
            return;
        }
        // Client BodyRotationControl clamps a moving head to travel yaw every tick.
        // Correct only the interpolated model pose, preserving physical heading and motion.
        float yaw = Mth.approachDegrees(mob.yHeadRotO, pose.yaw(), 40F);
        mob.setYHeadRot(yaw);
        mob.yBodyRot = Mth.rotateIfNecessary(mob.yBodyRot, yaw, mob.getMaxHeadYRot());
    }
}

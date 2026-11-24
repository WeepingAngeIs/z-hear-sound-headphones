package dev.mobsear.server;

import dev.mobsear.MobsEar;
import dev.mobsear.net.Cue;
import dev.mobsear.net.Packets;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

public final class RuleIsolationProof {
    public static void verify(ServerPlayer admin, boolean calls, boolean horror) {
        if (MobsEar.mobCallsAvailable() != calls || MobsEar.hordeRules().enabled() != horror)
            throw new AssertionError("Server rules did not change independently");
        var world = admin.serverLevel();
        var caller = world.getServer().getPlayerList().getPlayerByName("MicFixtureA");
        caller.teleportTo(admin.getX()+3, admin.getY(), admin.getZ());
        world.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        caller.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var zombie = EntityType.ZOMBIE.create(world);
        zombie.moveTo(caller.getX()+2, caller.getY(), caller.getZ());
        world.addFreshEntity(zombie);
        try {
            ServerVoice.forget(caller);
            var commands = world.getServer().getCommands();
            commands.performPrefixedCommand(caller.createCommandSourceStack(), "mobsear horror join");
            if (HordeMode.LIVE.optedIn(caller) != horror) throw new AssertionError("Voluntary Horror join ignored server policy");
            commands.performPrefixedCommand(caller.createCommandSourceStack(), "mobsear horror leave");
            if (HordeMode.LIVE.optedIn(caller)) throw new AssertionError("Voluntary Horror leave failed");
            int[] delivered = {0};
            ServerVoice.relay(caller, new Packets.Up(new byte[Packets.FRAME_BYTES]), List.of(admin), 32,
                    (listener, frame) -> delivered[0]++);
            ServerVoice.cue(caller, Cue.ZOMBIE, 32);
            if (delivered[0] != 1 || NamedMobReactions.isReacting(zombie) != calls)
                throw new AssertionError("Voice relay or the authoritative reaction gate failed");
            System.out.println("MOBSEAR_GUI_ISOLATION calls="+calls+" horror="+horror+" proximityRelay=true zombieReacted="+calls);
        } finally {
            zombie.discard();
        }
    }
}

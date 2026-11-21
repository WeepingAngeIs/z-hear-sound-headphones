package dev.mobsear.smoke;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

/** Final two-client barrier. Audio only travels through the production voice handlers. */
final class PitchSmoke {
    static final int NEAR = -30, FAR = -31;
    private static int phase;
    private static final Set<UUID> finished = new HashSet<>();

    static void start(ServerPlayer speaker, ServerPlayer listener) { begin(speaker, listener, NEAR); }

    private static void begin(ServerPlayer speaker, ServerPlayer listener, int stage) {
        phase = stage;
        finished.clear();
        place(speaker, 0);
        place(listener, stage == NEAR ? 4 : 48);
        for (var player : java.util.List.of(speaker,listener)) {
            PacketDistributor.sendToPlayer(player, new SmokeControl.Barrier(stage));
        }
    }

    private static void place(ServerPlayer player, int x) {
        var level = player.serverLevel().getServer().overworld();
        var feet = new BlockPos(x, 136, 160);
        level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(level,x + .5,136,160.5,0,0);
    }

    static void receive(ServerPlayer player, int stage) {
        var players = player.serverLevel().getServer().getPlayerList();
        var a = players.getPlayerByName(SmokeControl.SPEAKER);
        var b = players.getPlayerByName(SmokeControl.LISTENER);
        if (a == null || b == null || (player != a && player != b) || stage != phase || !finished.add(player.getUUID())) {
            throw SmokeControl.failure("Invalid pitch test acknowledgment");
        }
        if (finished.size() != 2) return;
        if (stage == NEAR) begin(a,b,FAR);
        else {
            System.out.println("MOBSEAR_PITCH_WIRE_PASS");
            System.out.println("MOBSEAR_MULTIPLAYER_PASS");
        }
    }
}

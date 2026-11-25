package dev.hearcheck;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(value="mic_join_probe", dist=Dist.DEDICATED_SERVER)
public final class JoinServer {
    private final Set<UUID> awaiting = new HashSet<>();

    public JoinServer() {
        NeoForge.EVENT_BUS.addListener(this::login);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String user = player.getGameProfile().getName();
        if (!Set.of("MicAbsent","MicPresent").contains(user)) return;
        boolean expected = user.equals("MicPresent");
        long registryKeys = BuiltInRegistries.REGISTRY.stream().flatMap(registry -> registry.keySet().stream())
                .filter(key -> key.getNamespace().equals("mobsear")).count();
        System.out.println("JOIN_PROOF server Mic registry keys="+registryKeys);
        if (registryKeys!=0) throw new IllegalStateException("A Mic registry entry still requires client synchronization");
        long channels = JoinCase.CHANNELS.stream().filter(player.connection::hasChannel).count();
        System.out.println("JOIN_PROOF server login "+user+" channels="+channels+" serverMic="+ModList.get().isLoaded("mobsear"));
        if (!ModList.get().isLoaded("mobsear") || channels != (expected ? JoinCase.CHANNELS.size() : 0)) return;
        var level = player.serverLevel();
        level.getChunkAt(JoinCase.LEVER);
        for (int x=7;x<=11;x++) for (int z=7;z<=9;z++)
            level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x,100,z),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(JoinCase.MARKER,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(JoinCase.LEVER,Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE,AttachFace.FLOOR));
        player.teleportTo(8.5,101,8.5);
        awaiting.add(player.getUUID());
    }

    private void tick(ServerTickEvent.Post event) {
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            if (!awaiting.contains(player.getUUID()) || player.getInventory().selected != 3 || player.isCreative()) continue;
            var level = player.serverLevel();
            var state = level.getBlockState(JoinCase.LEVER);
            if (state.is(Blocks.LEVER) && state.getValue(BlockStateProperties.POWERED)) {
                level.setBlockAndUpdate(JoinCase.MARKER,Blocks.EMERALD_BLOCK.defaultBlockState());
                awaiting.remove(player.getUUID());
                System.out.println("JOIN_PROOF server played "+player.getGameProfile().getName()+" survival slot=3 lever=on");
            }
        }
    }
}

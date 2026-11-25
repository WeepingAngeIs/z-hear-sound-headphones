package dev.hearcheck;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value="mic_join_probe", dist=Dist.CLIENT)
public final class JoinClient {
    private boolean inspected, clicked, done;
    private int ticks;

    public JoinClient() { NeoForge.EVENT_BUS.addListener(this::tick); }

    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof DisconnectedScreen screen) {
            String reason = screen.getNarrationMessage().getString().replaceAll("[\\r\\n]+"," ");
            finish(mc,"JOIN_REFUSED "+reason.substring(0,Math.min(600,reason.length())));
            return;
        }
        if (mc.player==null || mc.level==null || mc.gameMode==null || mc.getConnection()==null) return;
        String name = mc.player.getGameProfile().getName();
        boolean present = name.equals("MicPresent");
        if (!inspected) {
            boolean loaded = ModList.get().isLoaded("mobsear");
            int keys = (int)Arrays.stream(mc.options.keyMappings).filter(k -> k.getName().startsWith("key.mobsear.")).count();
            long channels = JoinCase.CHANNELS.stream().filter(mc.getConnection()::hasChannel).count();
            boolean clientClass;
            try {
                Class.forName("dev.mobsear.client.VoiceClient",false,getClass().getClassLoader());
                clientClass=true;
            } catch (ClassNotFoundException absent) { clientClass=false; }
            System.out.println("JOIN_PROOF client "+name+" micLoaded="+loaded+" micKeys="+keys+" micChannels="+channels+" micClass="+clientClass);
            if (loaded!=present || clientClass!=present || (keys>0)!=present || channels!=(present ? JoinCase.CHANNELS.size() : 0)) {
                finish(mc,"JOIN_INCORRECT features "+name); return;
            }
            inspected=true;
        }
        if (++ticks<60 || !mc.level.getBlockState(JoinCase.LEVER).is(Blocks.LEVER)) return;
        mc.player.getInventory().selected=3;
        if (!clicked && ticks>=65 && mc.player.position().distanceToSqr(Vec3.atCenterOf(JoinCase.LEVER))<=16) {
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(JoinCase.LEVER),
                    Direction.UP,JoinCase.LEVER,false));
            clicked=true;
        }
        if (clicked && ticks>=100 && mc.level.getBlockState(JoinCase.MARKER).is(Blocks.EMERALD_BLOCK))
            finish(mc,"JOIN_PASS "+name+" serverAcknowledgedWorldChange=true ticks="+ticks);
    }

    private void finish(Minecraft mc, String outcome) {
        System.out.println(outcome);
        done=true;
        mc.stop();
    }
}

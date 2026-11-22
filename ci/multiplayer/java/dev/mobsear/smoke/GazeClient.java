package dev.mobsear.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.Cue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Spider;
import org.lwjgl.glfw.GLFW;
import net.neoforged.neoforge.network.PacketDistributor;

/** Windows supplies the four synthetic recognizer decisions. Linux exercises their actual client-side
 * dispatch, authenticated audio connection, tracking packets, overlay and in-world rendering. */
final class GazeClient {
    private static int index, phase, ticks, stable;
    private static boolean active, sender, supplied;
    private static List<String> decisions;
    static void start(int next, boolean isSpeaker) {
        boolean before = next > GazeSmoke.SEND;
        index = (before ? GazeSmoke.BEFORE : GazeSmoke.SEND) - next;
        phase = next;
        sender = isSpeaker;
        supplied = false;
        ticks = stable = 0;
        active = true;
        if (before && sender && index == 0) {
            try {
                decisions = Files.readAllLines(Path.of(System.getenv("MOBSEAR_CUE_PROOF")));
            } catch (Exception e) { throw SmokeControl.failure("missing Windows recognizer decisions"); }
            if (!decisions.equals(List.of("ENDERMAN","SPIDER","MOBS","MOBS")))
                throw SmokeControl.failure("unexpected synthetic recognizer decisions");
            KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_N));
            FixtureSound.tone = true;
            VoiceClient.setVoiceActivated(true);
        }
    }

    static void tick() {
        if (!active) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ++ticks < 5) return;
        boolean before = phase > GazeSmoke.SEND;
        if (sender) {
            if (before) {
                if (VoiceClient.localState() != dev.mobsear.net.MicState.SPEAKING) return;
            } else {
                Cue cue = Cue.valueOf(decisions.get(GazeSmoke.DECISIONS[index]));
                if (!supplied) { supplyDecision(cue); supplied = true; return; }
                var fixedName = cue == Cue.MOBS ? Component.translatable("mobsear.speech.hostiles")
                        : Component.translatable("entity.minecraft." + cue.entityPath);
                String expected = Component.translatable("mobsear.speech.recognized", fixedName).getString();
                if (!expected.equals(overlay(mc.gui)) || ++stable < 3) return;
                capture("gaze-" + GazeSmoke.CASES[index] + "-feedback");
            }
        } else {
            Mob nearby = null;
            for (var entity : mc.level.entitiesForRendering()) {
                if (entity.distanceToSqr(mc.player) < 100 && (GazeSmoke.enderman(index)
                        ? entity instanceof EnderMan : entity instanceof Spider)) nearby = (Mob)entity;
            }
            if (nearby == null) return;
            double wanted = Math.toDegrees(Math.atan2(GazeSmoke.Z+.5-nearby.getZ(), GazeSmoke.X+24.5-nearby.getX()))-90;
            double error = Math.abs(Mth.wrapDegrees(nearby.getYHeadRot()-wanted));
            if (before ? error < 90 : error > 25) { stable = 0; return; }
            if (++stable < 3) return;
            capture("gaze-" + GazeSmoke.CASES[index] + (before ? "-before" : "-after"));
            System.out.printf(java.util.Locale.ROOT, "MOBSEAR_TRACKED_HEAD %s %s error=%.1f%n",
                    GazeSmoke.CASES[index], before ? "before" : "after", error);
        }
        active = false;
        PacketDistributor.sendToServer(new SmokeControl.Barrier(phase));
    }

    @SuppressWarnings("unchecked")
    static void supplyDecision(Cue cue) {
        try {
            var owner = VoiceClient.class.getDeclaredField("instance"); owner.setAccessible(true);
            var session = VoiceClient.class.getDeclaredField("session"); session.setAccessible(true);
            Object current = session.get(owner.get(null));
            var mailbox = current.getClass().getDeclaredField("cue"); mailbox.setAccessible(true);
            ((AtomicReference<net.minecraft.network.protocol.common.custom.CustomPacketPayload>)mailbox.get(current))
                    .set(new dev.mobsear.net.Packets.Call(cue));
        } catch (ReflectiveOperationException e) { throw SmokeControl.failure("recognizer mailbox " + e); }
    }
    static String overlay(Gui gui) {
        try {
            var field = Gui.class.getDeclaredField("overlayMessageString"); field.setAccessible(true);
            Component text = (Component)field.get(gui);
            return text == null ? "" : text.getString();
        } catch (ReflectiveOperationException e) { throw SmokeControl.failure("action bar " + e); }
    }
    private static void capture(String filename) {
        var folder = Path.of(System.getenv("MOBSEAR_SMOKE_DIR"), "visuals");
        try (var pic = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(folder);
            pic.writeToFile(folder.resolve(filename + ".png"));
        } catch (Exception e) { throw SmokeControl.failure("gaze framebuffer " + e); }
    }
}

package dev.mobsear.smoke;

import dev.mobsear.client.PitchSettingsScreen;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.client.VoicePreferences;
import dev.mobsear.client.VoiceSettingsScreen;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;

/** Runs in the freshly launched listener JVM using the preferences saved through the actual menu. */
final class PitchRestartSmoke {
    private static final UUID SPEAKER = UUID.nameUUIDFromBytes(("OfflinePlayer:" + SmokeControl.SPEAKER).getBytes(StandardCharsets.UTF_8));
    private static int step;
    static void tick() {
        var mc = Minecraft.getInstance();
        if (step == 4 || !VoiceClient.connected()) return;
        GLFW.glfwFocusWindow(mc.getWindow().getWindow());
        if (mc.screen instanceof PauseScreen pause) pause.onClose();
        var p = VoiceClient.options();
        if (step == 0) {
            check(!p.pitchEnabled() && p.pitchPreset().equals("squeaky") && p.pitchCents() == VoicePreferences.SQUEAKY_CENTS
                    && p.customPitchCents() == 300, "restored Squeaky, Custom and bypass");
            verifyUnrelated(p);
            MenuSmoke.pressG();
            step++;
        } else if (step == 1 && mc.screen instanceof VoiceSettingsScreen) {
            click("Voice Changer");
            step++;
        } else if (step == 2 && mc.screen instanceof PitchSettingsScreen) {
            click("Voice effect · Off (natural voice)");
            check(p.pitchEnabled() && p.pitchCents() == VoicePreferences.SQUEAKY_CENTS, "restored active preset");
            click("Custom");
            check(p.pitchEnabled() && p.pitchCents() == 300, "remembered manual setting");
            click("Reset to Defaults");
            check(!p.pitchEnabled() && p.pitchPreset().equals("natural") && p.customPitchCents() == 0, "reset defaults");
            verifyUnrelated(p);
            click("Back");
            step++;
        } else if (step == 3 && mc.screen instanceof VoiceSettingsScreen) {
            step++;
            System.out.println("MOBSEAR_GAME_RESTART_PASS saved-preset custom-slider bypass reset other-settings");
        }
    }

    private static void verifyUnrelated(VoicePreferences prefs) {
        check(prefs.theme().equals("amethyst_stars") && prefs.volume() == 50 && prefs.gain(SPEAKER) == 150,
                "unrelated settings after restart");
    }
    private static void click(String label) {
        var screen = Minecraft.getInstance().screen;
        AbstractWidget widget = MenuSmoke.find(screen,label,false);
        check(widget != null && widget.active, "button " + label);
        double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
        screen.mouseClicked(x,y,0);
        screen.mouseReleased(x,y,0);
    }
    private static void check(boolean ok,String message) {
        if (!ok) throw SmokeControl.failure("fresh Minecraft JVM: " + message);
    }
}

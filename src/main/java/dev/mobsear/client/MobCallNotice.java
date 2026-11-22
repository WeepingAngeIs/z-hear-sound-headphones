package dev.mobsear.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** The private mob-call strip shares NeoForge's health and armor layout on each frame. */
final class MobCallNotice {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mobsear", "mob_call_notice");
    private static final ResourceLocation KITTY = ResourceLocation.fromNamespaceAndPath("mobsear", "textures/gui/notice_kitten.png");
    private static final Style FACE = Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("mobsear", "notice"));
    private static final int LIFETIME = 60;
    private static Component message, diagnostic;
    private static int messageEnd, diagnosticEnd;
    private static boolean confirmed;

    private MobCallNotice() {}

    static void register(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.SELECTED_ITEM_NAME, ID, MobCallNotice::render);
    }

    static void clear() {
        message = diagnostic = null;
        confirmed = false;
    }

    static void clearMatch() {
        if (confirmed) message = null;
        diagnostic = null;
        confirmed = false;
    }

    static void show(Component status) {
        diagnostic = null;
        confirmed = false;
        message = status;
        messageEnd = Minecraft.getInstance().gui.getGuiTicks() + LIFETIME;
    }

    static void ready(Component status) {
        if (!confirmed || current() == null) show(status);
    }

    static void confirm(Component status) {
        confirmed = true;
        message = status;
        messageEnd = Minecraft.getInstance().gui.getGuiTicks() + LIFETIME;
    }

    static void diagnose(Component local) {
        diagnose(local, false);
    }

    static void diagnose(Component local, boolean inProgress) {
        confirmed = false;
        message = null;
        diagnostic = local;
        diagnosticEnd = inProgress ? -1 : Minecraft.getInstance().gui.getGuiTicks() + LIFETIME;
    }

    static void clearDiagnostic() {
        diagnostic = null;
    }

    static Component localMatch() {
        if (diagnostic != null && ((diagnosticEnd >= 0 && Minecraft.getInstance().gui.getGuiTicks() >= diagnosticEnd)
                || !VoiceClient.options().recognitionDiagnostics())) diagnostic = null;
        return diagnostic;
    }

    static Component current() {
        if (message != null && Minecraft.getInstance().gui.getGuiTicks() >= messageEnd) message = null;
        return message != null ? message : localMatch();
    }

    private static void render(GuiGraphics g, DeltaTracker tick) {
        var mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || mc.player == null || !mc.player.isAlive()) return;
        Component main = current();
        if (main == null) main = VoiceClient.speechTestPrompt();
        if (main == null) return;
        Component local = localMatch();
        var font = mc.font;
        int x = Math.max(2, g.guiWidth() / 2 - 91);
        int available = Math.min(182, g.guiWidth() - x - 2) - 27;
        if (available < 16) return;
        var lines = font.split(Component.empty().setStyle(FACE).append(main), available);
        List<FormattedCharSequence> detail = local == null || local == main ? List.of()
                : font.split(Component.empty().setStyle(FACE).append(local), available);
        int textWidth = 0;
        for (var line : lines) textWidth = Math.max(textWidth, font.width(line));
        for (var line : detail) textWidth = Math.max(textWidth, font.width(line));
        int right = x + textWidth + 27;
        int height = Math.max(17, (lines.size() + detail.size()) * font.lineHeight + 7);
        int occupied = mc.gui.leftHeight;
        if (right > g.guiWidth() / 2 + 9) occupied = Math.max(occupied, mc.gui.rightHeight);
        // Each vanilla bar allocates 10 pixels including its line spacing. Leave two clear
        // pixels above its sprite and room for the regeneration heart's two-pixel bounce.
        int bottom = g.guiHeight() - occupied + 8;
        if (mc.player.getArmorValue() == 0 && mc.player.hasEffect(MobEffects.REGENERATION)) bottom -= 2;
        int top = bottom - height;
        if (top < 1) return;

        // One-pixel stepped corners, rose piping, and a pale stitched edge.
        g.fill(x + 2, top, right - 2, bottom, 0xffd777a1);
        g.fill(x + 1, top + 1, right - 1, bottom - 1, 0xffd777a1);
        g.fill(x, top + 2, right, bottom - 2, 0xffd777a1);
        g.fill(x + 2, top + 1, right - 2, bottom - 1, 0xff412438);
        g.fill(x + 1, top + 2, right - 1, bottom - 2, 0xff412438);
        g.fill(x + 3, top + 1, right - 3, top + 2, 0xff92516e);
        g.fill(x + 3, top, x + 11, top + 1, 0xffffc6dc);
        g.fill(x + 3, bottom - 2, right - 3, bottom - 1, 0xff613047);
        int iconY = top + (height - 13) / 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(KITTY, x + 4, iconY, 0, 0, 14, 13, 14, 13);
        RenderSystem.disableBlend();
        g.fill(x + 20, top + 4, x + 21, bottom - 4, 0xff92516e);
        int y = top + 4;
        for (var line : lines) {
            g.drawString(font, line, x + 23, y, 0xffffedf5, false);
            y += font.lineHeight;
        }
        for (var line : detail) {
            g.drawString(font, line, x + 23, y, 0xffffbad6, false);
            y += font.lineHeight;
        }
        // The next native layer (item name or action bar) can use the remaining space.
        mc.gui.leftHeight = Math.max(mc.gui.leftHeight, g.guiHeight() - top + 2);
    }
}

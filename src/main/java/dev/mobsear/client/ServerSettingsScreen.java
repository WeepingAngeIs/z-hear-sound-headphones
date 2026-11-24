package dev.mobsear.client;

import dev.mobsear.net.Packets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;

import static dev.mobsear.client.VoiceSettingsScreen.t;

final class ServerSettingsScreen extends Screen {
    private final VoiceSettingsScreen parent;
    private final VoiceWidgets.Palette colors;
    private final ResourceLocation scene;
    private VoiceWidgets.Button calls, horror;
    private Packets.ServerRules shown;
    private int x, y, w, h;
    private boolean pending;
    private int ticks;

    ServerSettingsScreen(VoiceSettingsScreen parent, VoicePreferences prefs) {
        super(t("server.title"));
        this.parent = parent;
        colors = VoiceWidgets.Palette.forTheme(prefs.theme());
        var theme = MenuTheme.fromId(prefs.theme());
        scene = theme.hasScene() ? ResourceLocation.fromNamespaceAndPath("mobsear",
                "textures/gui/themes/" + theme.id() + ".png") : null;
    }

    @Override protected void init() {
        w = Math.min(246, width - 8);
        h = Math.min(120, height - 8);
        x = (width - w) / 2;
        y = (height - h) / 2;
        calls = button(y + 26, "server.calls", () -> send(shown.calls() ? Packets.RuleAction.CALLS_OFF : Packets.RuleAction.CALLS_ON));
        horror = button(y + 48, "server.horror", () -> send(shown.horror() ? Packets.RuleAction.HORROR_OFF : Packets.RuleAction.HORROR_ON));
        var back = button(y + h - 21, "server.title", this::onClose);
        back.setMessage(CommonComponents.GUI_BACK);
        send(Packets.RuleAction.QUERY);
    }

    private VoiceWidgets.Button button(int top, String key, Runnable action) {
        return addRenderableWidget(new VoiceWidgets.Button(x + 8, top, w - 16, 17, t(key), colors,
                VoiceWidgets.Style.ROW, false, null, null, action));
    }

    private void send(Packets.RuleAction action) {
        shown = VoiceClient.rules();
        pending = true;
        update();
        VoiceClient.requestRule(action);
    }

    @Override public void tick() {
        if (VoiceClient.rules() == null || !VoiceClient.rules().editable()) {
            onClose();
            return;
        }
        if (++ticks % 100 == 0) VoiceClient.requestRule(Packets.RuleAction.QUERY);
        if (shown != VoiceClient.rules()) {
            shown = VoiceClient.rules();
            pending = false;
            update();
        }
    }

    private void update() {
        calls.active = horror.active = !pending && shown != null && shown.editable();
        calls.setMessage(t("choice", t("server.calls"), value(shown != null && shown.calls())));
        horror.setMessage(t("choice", t("server.horror"), value(shown != null && shown.horror())));
    }

    private net.minecraft.network.chat.Component value(boolean on) {
        return shown == null ? t("server.wait") : on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        VoiceSettingsScreen.drawFrame(g, colors, scene, parent.sceneFrame(), font, title,
                width, height, x, y, w, h, y + 21);

    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}

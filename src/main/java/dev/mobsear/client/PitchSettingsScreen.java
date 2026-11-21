package dev.mobsear.client;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import static dev.mobsear.client.VoicePreferences.PITCH_RANGE_CENTS;
import static dev.mobsear.client.VoiceSettingsScreen.t;

/** Outgoing voice controls share the player's selected Minecraft frame and panorama. */
public final class PitchSettingsScreen extends Screen {
    private final VoiceSettingsScreen parent;
    private final VoicePreferences prefs;
    private final VoiceWidgets.Palette colors;
    private final ResourceLocation scene;
    private int x, y, widthPanel, heightPanel, controlX, controlW;
    private VoiceWidgets.Button bypass;
    private VoiceWidgets.Slider slider;
    private final Map<String, VoiceWidgets.Button> presets = new LinkedHashMap<>();

    PitchSettingsScreen(VoiceSettingsScreen parent, VoicePreferences prefs) {
        super(t("pitch.title"));
        this.parent = parent;
        this.prefs = prefs;
        colors = VoiceWidgets.Palette.forTheme(prefs.theme());
        var theme = MenuTheme.fromId(prefs.theme());
        scene = theme.hasScene() ? ResourceLocation.fromNamespaceAndPath("mobsear",
                "textures/gui/themes/" + theme.id() + ".png") : null;
    }

    @Override protected void init() {
        widthPanel = Math.min(340, width - 12);
        heightPanel = Math.min(184, height - 8);
        x = (width - widthPanel) / 2;
        y = (height - heightPanel) / 2;
        controlX = x + 12;
        controlW = widthPanel - 24;
        presets.clear();
        bypass = button(controlX, y + 34, controlW, t("pitch.title"), () -> {
            prefs.setPitchEnabled(!prefs.pitchEnabled());
            updateBypass();
        });
        updateBypass();
        slider = addRenderableWidget(new VoiceWidgets.Slider(controlX, y + 61, controlW, 20,
                t("pitch.slider"), prefs.pitchCents() + PITCH_RANGE_CENTS, 2 * PITCH_RANGE_CENTS, colors,
                v -> t("pitch.value", amount(v)), v -> {
                    prefs.setPitchCents(v - PITCH_RANGE_CENTS);
                    prefs.setPitchEnabled(true);
                    updateSelection();
                }, this::amount));
        String[] choices = {"deep","natural","squeaky","custom"};
        for (int i = 0; i < choices.length; i++) {
            int start = (controlW + 2) * i / choices.length;
            int end = (controlW + 2) * (i + 1) / choices.length - 2;
            preset(controlX + start, end - start, choices[i]);
        }
        updateSelection();
        button(controlX, y + heightPanel - 26, Math.min(125, controlW - 76), t("pitch.reset"), () -> {
            prefs.resetPitch();
            slider.showValue(PITCH_RANGE_CENTS);
            updateSelection();
        });
        button(controlX + controlW - 67, y + heightPanel - 26, 67, CommonComponents.GUI_BACK, this::onClose);
    }

    private Component amount(int value) {
        int cents = value - PITCH_RANGE_CENTS;
        if (cents == 0) return t("pitch.natural");
        return t("pitch.semitones", String.format(Locale.ROOT, "%+.2f", cents / 100.0));
    }

    private void updateBypass() {
        bypass.setMessage(t("pitch.effect", t(prefs.pitchEnabled() ? "value.on" : "pitch.bypass")));
    }

    private void updateSelection() {
        updateBypass();
        presets.forEach((key, widget) -> widget.setSelected(key.equals(prefs.pitchPreset())));
    }

    private void preset(int at, int w, String key) {
        presets.put(key, button(at, y + 111, w, t("pitch." + key), () -> {
            prefs.setPitchPreset(key);
            prefs.setPitchEnabled(true);
            slider.showValue(prefs.pitchCents() + PITCH_RANGE_CENTS);
            updateSelection();
        }));
    }

    private VoiceWidgets.Button button(int at, int top, int w, Component name, Runnable click) {
        return addRenderableWidget(new VoiceWidgets.Button(at, top, w, 19, name, colors,
                VoiceWidgets.Style.ROW, false, null, null, click));
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (minecraft.level == null) renderPanorama(g, partial);
        VoiceSettingsScreen.drawFrame(g, colors, scene, parent.sceneFrame(), font, title,
                width, height, x, y, widthPanel, heightPanel, y + 31);
        int left = controlX, right = controlX + controlW;
        g.fill(left - 3, y + 56, right + 3, y + 98, 0xab17141b);
        int textY = y + 85;
        g.drawString(font, t("pitch.deep"), left + 2, textY, colors.text(), false);
        g.drawCenteredString(font, t("pitch.natural"), (left + right) / 2, textY, colors.text());
        var high = t("pitch.squeaky");
        g.drawString(font, high, right - font.width(high) - 2, textY, colors.text(), false);
        g.drawString(font, t("pitch.presets"), left, y + 101, colors.text(), true);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { prefs.save(); }
}

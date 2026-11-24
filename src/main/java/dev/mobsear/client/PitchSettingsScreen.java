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
    private VoiceControlList scrolling;
    private VoiceWidgets.Button bypass;
    private VoiceWidgets.Slider slider, reverb;
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
        widthPanel = Math.min(274, width - 8);
        heightPanel = Math.min(152, height - 8);
        x = (width - widthPanel) / 2;
        y = (height - heightPanel) / 2;
        controlX = x + 12;
        controlW = widthPanel - 24;
        presets.clear();
        double offset = scrolling == null ? 0 : scrolling.getScrollAmount();
        scrolling = heightPanel < 146 ? addRenderableWidget(new VoiceControlList(minecraft, controlX,
                y + 23, controlW, Math.max(20, heightPanel - 48), 16)) : null;
        bypass = button(controlX, y + 24, controlW, t("pitch.title"), () -> {
            prefs.setPitchEnabled(!prefs.pitchEnabled());
            updateBypass();
        });
        updateBypass();
        slider = control(new VoiceWidgets.Slider(controlX, y + 45, controlW, 16,
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
        reverb = control(new VoiceWidgets.Slider(controlX, y + 109, controlW, 16,
                t("pitch.reverb"), prefs.reverbAmount(), 100, colors,
                v -> t("pitch.reverb.value", reverbLabel(v)), v -> {
                    prefs.setReverbAmount(v);
                    if (v > 0) prefs.setPitchEnabled(true);
                    updateBypass();
                }, this::reverbLabel));
        updateSelection();
        button(controlX, y + heightPanel - 21, Math.min(125, controlW - 76), t("pitch.reset"), () -> {
            prefs.resetPitch();
            slider.showValue(PITCH_RANGE_CENTS);
            reverb.showValue(0);
            updateSelection();
        });
        if (scrolling != null) scrolling.setScrollAmount(offset);
        addRenderableWidget(new VoiceWidgets.Button(controlX + controlW - 67, y + heightPanel - 21, 67, 16, CommonComponents.GUI_BACK, colors, VoiceWidgets.Style.ROW, false, null, null, this::onClose));
    }

    private Component amount(int value) {
        int cents = value - PITCH_RANGE_CENTS;
        if (cents == 0) return t("pitch.natural");
        return t("pitch.semitones", String.format(Locale.ROOT, "%+.2f", cents / 100.0));
    }

    private Component reverbLabel(int value) {
        return value == 0 ? t("value.off") : t("percent", value);
    }

    private void updateBypass() {
        bypass.setMessage(t("pitch.effect", t(prefs.pitchEnabled() ? "value.on" : "pitch.bypass")));
    }

    private void updateSelection() {
        updateBypass();
        presets.forEach((key, widget) -> widget.setSelected(key.equals(prefs.pitchPreset())));
    }

    private void preset(int at, int w, String key) {
        presets.put(key, button(at, y + 89, w, t("pitch." + key), () -> {
            prefs.setPitchPreset(key);
            prefs.setPitchEnabled(true);
            slider.showValue(prefs.pitchCents() + PITCH_RANGE_CENTS);
            updateSelection();
        }));
    }

    private VoiceWidgets.Button button(int at, int top, int w, Component name, Runnable click) {
        return control(new VoiceWidgets.Button(at, top, w, 16, name, colors,
                VoiceWidgets.Style.ROW, false, null, null, click));
    }

    private <T extends net.minecraft.client.gui.components.AbstractWidget> T control(T widget) {
        if (scrolling == null) return addRenderableWidget(widget);
        scrolling.put(widget);
        return widget;
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (minecraft.level == null) renderPanorama(g, partial);
        VoiceSettingsScreen.drawFrame(g, colors, scene, parent.sceneFrame(), font, title,
                width, height, x, y, widthPanel, heightPanel, y + 21);
        if (scrolling != null) return;
        int left = controlX, right = controlX + controlW;
        g.fill(left - 3, y + 42, right + 3, y + 75, 0xab17141b);
        int textY = y + 64;
        g.drawString(font, t("pitch.deep"), left + 2, textY, colors.text(), false);
        g.drawCenteredString(font, t("pitch.natural"), (left + right) / 2, textY, colors.text());
        var high = t("pitch.squeaky");
        g.drawString(font, high, right - font.width(high) - 2, textY, colors.text(), false);
        g.drawString(font, t("pitch.presets"), left, y + 78, colors.text(), true);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { prefs.save(); }
}

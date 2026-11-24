package dev.mobsear.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mobsear.net.Packets;
import dev.mobsear.client.VoiceWidgets.Palette;
import dev.mobsear.client.VoiceWidgets.Style;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;

public final class VoiceSettingsScreen extends Screen {
    private static final List<String> THEMES = List.of("uwu", "emo", "vanilla", "amethyst");
    private static final SystemToast.SystemToastId SAVE_ERROR = new SystemToast.SystemToastId();
    private final Screen parent;
    private final VoicePreferences prefs;
    private final Predicate<UUID> speaking;
    private final Map<String, AbstractWidget> controls = new HashMap<>();
    private List<VoicePreferences.Device> inputs, outputs;
    private Palette colors;
    private OnlineVoiceList players;
    private VoiceControlList audio;
    private MenuTheme theme;
    private ResourceLocation scene;
    private boolean shownAdmin, inlineAppearance;
    private int rosterTicks, ruleTicks;
    private int left, top, panelWidth, panelHeight, leftX, leftWidth, rightX, rightWidth;
    private int bodyY, appearanceY, footerY, rowHeight, sectionHeight;
    private String focusAfterRefresh;

    public VoiceSettingsScreen(Screen parent, VoicePreferences prefs, BooleanSupplier mobCalls,
                               Consumer<Boolean> changeMobCalls, Predicate<UUID> isSpeaking) {
        super(t("title"));
        this.parent = parent;
        this.prefs = prefs;
        this.speaking = isSpeaking;
    }

    static Component t(String key, Object... args) {
        return Component.translatable("mobsear.settings." + key, args);
    }

    @Override protected void init() {
        controls.clear();
        theme = MenuTheme.fromId(prefs.theme());
        scene = theme.hasScene() ? ResourceLocation.fromNamespaceAndPath("mobsear",
                "textures/gui/themes/" + theme.id() + ".png") : null;
        colors = Palette.forTheme(prefs.theme());
        panelWidth = Math.min(378, width - 8);
        panelHeight = Math.min(210, height - 8);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int inset = 6;
        int gap = 4;
        leftX = left + inset;
        int contentWidth = panelWidth - inset * 2;
        leftWidth = (contentWidth - gap) * 48 / 100;
        rightX = leftX + leftWidth + gap;
        rightWidth = contentWidth - leftWidth - gap;
        bodyY = top + 21;
        footerY = top + panelHeight - 22;
        sectionHeight = 13;
        rowHeight = 16;
        int themeHeight = 14;
        inlineAppearance = panelHeight < 168;
        appearanceY = inlineAppearance ? footerY : footerY - sectionHeight - 3 * (themeHeight + 2) - 5;
        int x = leftX + 3, w = leftWidth - 6;
        int y = bodyY + sectionHeight + 2;
        if (inputs == null) inputs = deviceOptions(true);
        if (outputs == null) outputs = deviceOptions(false);
        double audioScroll = audio == null ? 0 : audio.getScrollAmount();
        audio = new VoiceControlList(minecraft, leftX + 1, y, leftWidth - 2, Math.max(18, appearanceY - y - 3), rowHeight);
        add("audio_list", audio);
        addDevice(true, x, y, w);
        addDevice(false, x, y, w);
        addAudio("volume", new VoiceWidgets.Slider(x, y, w, rowHeight, t("volume.label"), prefs.volume(),
                colors, n -> t("volume", n), prefs::setVolume));
        toggle("mute", prefs.muted(), true, prefs::setMuted);
        toggle("deafen", prefs.deafened(), true, prefs::setDeafened);
        choice("activation", prefs.voiceActivated() ? "voice" : "ptt", true,
                () -> VoiceClient.setVoiceActivated(!prefs.voiceActivated()));
        addAudio("sensitivity", new VoiceWidgets.Slider(x, y, w, rowHeight, t("sensitivity.label"),
                prefs.sensitivity(), 100, colors, n -> t("sensitivity", n), prefs::setSensitivity));
        toggle("noise", prefs.noiseSuppression(), true, prefs::setNoiseSuppression);
        toggle("meter", VoiceClient.testingMic(), VoiceClient.connected() && !prefs.muted() && !prefs.deafened(),
                VoiceClient::setTestingMic);
        toggle("recognition_diagnostics", prefs.recognitionDiagnostics(), true, prefs::setRecognitionDiagnostics);
        toggle("call_debug_log", prefs.callDebugLog(), true, prefs::setCallDebugLog);
        cycle("mic_style", prefs.micStyle(), List.of("auto","uwu","emo","vanilla","amethyst"), prefs::setMicStyle);
        cycle("accent", prefs.accent(), List.of("auto","rose","gold","mint","aqua","violet"), prefs::setAccent);
        toggle("hud", prefs.hudVisible(), true, prefs::setHudVisible);
        toggle("nameplates", prefs.nameplates(), true, prefs::setNameplates);
        cycle("motion", prefs.effects(), List.of("full","reduced","off"), prefs::setEffects);

        int themeY = appearanceY + sectionHeight + 2;
        int half = (w - 2) / 2;
        for (int i = 0; i < THEMES.size(); i++) {
            String id = THEMES.get(i);
            int tx = x + (i % 2) * (half + 2), tw = i % 2 == 0 ? half : w - half - 2;
            appearanceButton(id, tx, themeY + (i / 2) * (themeHeight + 2), tw, themeHeight, t("theme." + id),
                    Style.ROW, theme.family().equals(id), () -> {
                        prefs.selectFamily(id);
                        refresh(id);
                    });
        }
        appearanceButton("backdrop", x, themeY + 2 * (themeHeight + 2), w, themeHeight,
                t("backdrop", theme.label()), Style.ROW, false, () -> {
                    prefs.setTheme(theme.next().id());
                    refresh("backdrop");
                }).setTooltip(Tooltip.create(t("backdrop.hint")));

        int listY = bodyY + sectionHeight + 1;
        double scroll = players == null ? 0 : players.getScrollAmount();
        players = new OnlineVoiceList(minecraft, rightX + 1, listY, rightWidth - 2,
                Math.max(22, footerY - listY - 5), prefs, speaking, colors);
        players.setScrollAmount(scroll);
        add("players", players);
        int doneWidth = Math.min(72, rightWidth);
        addButton("voice_changer", leftX, footerY, Math.min(96, leftWidth), 17, t("pitch.title"),
                Style.ROW, false, () -> minecraft.setScreen(new PitchSettingsScreen(this, prefs)));
        int serverX = leftX + Math.min(96, leftWidth) + 3;
        int serverW = Math.min(98, rightX + rightWidth - doneWidth - serverX - 3);
        shownAdmin = VoiceClient.rules() != null && VoiceClient.rules().editable();
        if (shownAdmin) {
            var server = new VoiceWidgets.Button(serverX, footerY, Math.max(1, serverW), 17, t("server.title"),
                    colors, Style.ROW, false, null, null, () -> minecraft.setScreen(new ServerSettingsScreen(this, prefs)));
            if (serverW < 78) addAudio("server_settings", server);
            else add("server_settings", server);
        }
        audio.setScrollAmount(audioScroll);
        addButton("done", rightX + rightWidth - doneWidth, footerY, doneWidth, 17, CommonComponents.GUI_DONE,
                Style.PRIMARY, false, this::onClose);
    }

    private VoiceWidgets.Button appearanceButton(String id, int x, int y, int w, int h, Component label,
                                                  Style style, boolean selected, Runnable action) {
        var button = new VoiceWidgets.Button(x,y,w,h,label,colors,style,selected,null,null,action);
        return inlineAppearance ? addAudio(id,button) : add(id,button);
    }

    private List<VoicePreferences.Device> deviceOptions(boolean mic) {
        var devices = new ArrayList<VoicePreferences.Device>();
        devices.add(new VoicePreferences.Device("", t("default").getString()));
        for (var device : prefs.devices(mic)) {
            if (!device.id().isEmpty()) devices.add(device);
        }
        return List.copyOf(devices);
    }

    private void addDevice(boolean mic, int x, int y, int w) {
        String key = mic ? "microphone" : "output";
        var devices = mic ? inputs : outputs;
        String id = prefs.device(mic);
        int found = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).id().equals(id)) { found = i; break; }
        }
        Component detail = found < 0 ? t("device.missing") : Component.literal(devices.get(found).label());
        int next = (found + 1) % devices.size();
        var widget = rowButton(key, x, y, w, t(key, detail), t(key + ".label"), detail, () -> {
            prefs.setDevice(mic, devices.get(next).id());
            refresh(key);
        });
        widget.active = devices.size() > 1 || found < 0;
        widget.setTooltip(Tooltip.create(t(key, detail)));
    }

    private void refresh(String key) {
        focusAfterRefresh = key;
        rebuildWidgets();
        restoreFocus(key);
    }

    private void restoreFocus(String key) {
        var widget = controls.get(key);
        if (widget == null) return;
        if (children().contains(widget)) setFocused(widget);
        else { setFocused(audio); audio.focus(widget); }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        boolean handled = super.mouseClicked(x, y, button);
        // Vanilla assigns focus after onPress returns, even if that press rebuilt the widgets.
        if (handled && getFocused() != null && !children().contains(getFocused())) {
            restoreFocus(focusAfterRefresh);
        }
        return handled;
    }

    @Override public void tick() {
        boolean admin = VoiceClient.rules() != null && VoiceClient.rules().editable();
        if (admin != shownAdmin) rebuildWidgets();
        if (ruleTicks++ % 100 == 0 && VoiceClient.canRequestRules()) VoiceClient.requestRule(Packets.RuleAction.QUERY);
        if (++rosterTicks >= 10) {
            rosterTicks = 0;
            players.refreshRoster();
        }
    }

    private VoiceWidgets.Button addButton(String key, int x, int y, int w, int h, Component message,
                                          Style style, boolean selected, Runnable action) {
        return add(key, new VoiceWidgets.Button(x, y, w, h, message, colors, style, selected, null, null, action));
    }

    private VoiceWidgets.Button rowButton(String key, int x, int y, int w, Component message, Component label,
                                          Component detail, Runnable action) {
        return addAudio(key, new VoiceWidgets.Button(x, y, w, rowHeight, message, colors,
                Style.ROW, false, label, detail, action));
    }

    private void toggle(String key, boolean enabled, boolean available, Consumer<Boolean> change) {
        choice(key, enabled ? "on" : "off", available, () -> change.accept(!enabled));
    }

    private void cycle(String key, String value, List<String> choices, Consumer<String> change) {
        choice(key, value, true, () -> change.accept(choices.get((choices.indexOf(value) + 1) % choices.size())));
    }

    private void choice(String key, String value, boolean available, Runnable action) {
        var detail = t("value." + value);
        var button = rowButton(key, 0, 0, leftWidth, t("choice", t(key + ".label"), detail),
                t(key + ".label"), detail, () -> { action.run(); refresh(key); });
        button.active = available;
        button.setTooltip(Tooltip.create(t(key + ".hint")));
    }

    private <T extends AbstractWidget> T addAudio(String key, T widget) {
        controls.put(key, widget);
        audio.put(widget);
        return widget;
    }

    private <T extends AbstractWidget> T add(String key, T widget) {
        controls.put(key, widget);
        return addRenderableWidget(widget);
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level == null) renderPanorama(g, partialTick);
        drawFrame(g, colors, scene, sceneFrame(), font, getTitle(), width, height, left, top, panelWidth, panelHeight, bodyY);
        section(g, leftX, bodyY, leftWidth, appearanceY - 2, t("audio"));
        if (!inlineAppearance) section(g, leftX, appearanceY, leftWidth, footerY - 4, t("appearance"));
        section(g, rightX, bodyY, rightWidth, footerY - 4, t("players"));
        if (VoiceClient.testingMic()) {
            int end = leftX + leftWidth - 5;
            int x = end - Math.min(42, leftWidth / 3);
            int y = bodyY + 3;
            g.fill(x, y, end, y + 5, colors.control());
            int bar = (int)((end - x) * Math.min(1, VoiceClient.inputLevel()));
            if (bar > 0) g.fill(x, y, x + bar, y + 5, colors.accent());
        }
    }

    static void drawFrame(GuiGraphics g, Palette colors, ResourceLocation scene, int frame,
                          net.minecraft.client.gui.Font font, Component title, int width, int height,
                          int left, int top, int panelWidth, int panelHeight, int bodyY) {
        int right = left + panelWidth, bottom = top + panelHeight;
        g.fillGradient(0, 0, width, height, colors.backdropTop(), colors.backdropBottom());
        g.fill(left + 2, top + 3, right + 2, bottom + 3, 0x89000000);
        Screen.renderMenuBackgroundTexture(g, MENU_BACKGROUND, left, top, 0, 0, panelWidth, panelHeight);
        g.fill(left, top, right, bottom, 0xc0000000 | (colors.panel() & 0xffffff));
        g.renderOutline(left, top, panelWidth, panelHeight, colors.line());
        g.fillGradient(left + 1, top + 1, right - 1, bodyY - 3, colors.header(), colors.panel());
        if (scene != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.blit(scene, left + 1, top + 2, panelWidth - 2, panelHeight - 4,
                    0f, frame * 96f, 192, 96, 192, 768);
            // The small panorama in the header lets players see all of a scene even with a full roster.
            int stampH = bodyY - top - 8;
            g.blit(scene, right - 6 - stampH * 2, top + 4, stampH * 2, stampH,
                    0f, frame * 96f, 192, 96, 192, 768);
            RenderSystem.disableBlend();
        }
        g.fill(left + 1, top + 1, right - 1, top + 2, colors.accent());
        int titleY = top + (bodyY - top - 9) / 2;
        if (scene != null) g.fill(left + panelWidth / 2 - 50, titleY - 3,
                left + panelWidth / 2 + 50, titleY + 11, 0xa018131b);
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.BOLD), left + panelWidth / 2,
                titleY, colors.text());
    }

    int sceneFrame() { return prefs.effects().equals("full") ? (int)(Util.getMillis() / 400L % 8L) : 0; }

    private void section(GuiGraphics g, int x, int y, int w, int bottom, Component title) {
        if (scene == null) Screen.renderMenuBackgroundTexture(g, MENU_BACKGROUND, x, y, 0, 0, w, bottom - y);
        int tint = scene == null ? 0xdb000000 : 0x52000000;
        g.fill(x, y, x + w, bottom, tint | (colors.panel() & 0xffffff));
        g.renderOutline(x, y, w, bottom - y, colors.line());
        g.fillGradient(x + 1, y + 1, x + w - 1, y + sectionHeight, colors.header(), colors.control());
        g.fill(x + 1, y + sectionHeight - 1, x + w - 1, y + sectionHeight, colors.line());
        VoiceWidgets.text(g, font, title.copy().withStyle(ChatFormatting.BOLD), x + 5, x + w - 4,
                y + (sectionHeight - 9) / 2, colors.text(), false);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void removed() {
        VoiceClient.setTestingMic(false);
        if (!prefs.save()) SystemToast.add(minecraft.getToasts(), SAVE_ERROR, getTitle(), t("save_error"));
    }
}

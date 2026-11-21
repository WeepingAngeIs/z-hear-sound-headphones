package dev.mobsear.client;

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

public final class VoiceSettingsScreen extends Screen {
    private static final List<String> THEMES = List.of("uwu", "emo", "vanilla", "amethyst");
    private static final SystemToast.SystemToastId SAVE_ERROR = new SystemToast.SystemToastId();
    private final Screen parent;
    private final VoicePreferences prefs;
    private final BooleanSupplier mobCalls;
    private final Consumer<Boolean> changeMobCalls;
    private final Predicate<UUID> speaking;
    private final Map<String, AbstractWidget> controls = new HashMap<>();
    private List<VoicePreferences.Device> inputs, outputs;
    private Palette colors;
    private OnlineVoiceList players;
    private int rosterTicks;
    private int left, top, panelWidth, panelHeight, leftX, leftWidth, rightX, rightWidth;
    private int bodyY, appearanceY, footerY, rowHeight, sectionHeight;
    private String focusAfterRefresh;

    public VoiceSettingsScreen(Screen parent, VoicePreferences prefs, BooleanSupplier mobCalls,
                               Consumer<Boolean> changeMobCalls, Predicate<UUID> isSpeaking) {
        super(t("title"));
        this.parent = parent;
        this.prefs = prefs;
        this.mobCalls = mobCalls;
        this.changeMobCalls = changeMobCalls;
        this.speaking = isSpeaking;
    }

    static Component t(String key, Object... args) {
        return Component.translatable("mobsear.settings." + key, args);
    }

    @Override protected void init() {
        controls.clear();
        colors = Palette.forTheme(prefs.theme());
        panelWidth = Math.min(550, Math.max(120, width - 8));
        panelHeight = Math.min(280, Math.max(100, height - 8));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int inset = panelWidth >= 400 ? 10 : 6;
        int gap = panelWidth >= 400 ? 7 : 4;
        leftX = left + inset;
        int contentWidth = panelWidth - inset * 2;
        leftWidth = (contentWidth - gap) * 43 / 100;
        rightX = leftX + leftWidth + gap;
        rightWidth = contentWidth - leftWidth - gap;
        bodyY = top + (panelHeight < 186 ? 22 : 32);
        footerY = top + panelHeight - (panelHeight < 186 ? 21 : 26);
        sectionHeight = panelHeight < 186 ? 13 : 16;
        int room = footerY - bodyY - sectionHeight * 2 - 20;
        rowHeight = Math.clamp(room / 6, 13, 26);
        int x = leftX + 3, w = leftWidth - 6;
        int y = bodyY + sectionHeight + 2;
        if (inputs == null) inputs = deviceOptions(true);
        if (outputs == null) outputs = deviceOptions(false);
        addDevice(true, x, y, w);
        y += rowHeight + 2;
        addDevice(false, x, y, w);
        y += rowHeight + 2;
        add("volume", new VoiceWidgets.Slider(x, y, w, rowHeight, t("volume.label"), prefs.volume(),
                colors, n -> t("volume", n), prefs::setVolume));
        y += rowHeight + 2;
        addMobCalls(x, y, w);
        appearanceY = y + rowHeight + 5;
        int themeY = appearanceY + sectionHeight + 2;
        int themeHeight = Math.min(22, rowHeight);
        int half = (w - 2) / 2;
        for (int i = 0; i < THEMES.size(); i++) {
            String id = THEMES.get(i);
            int tx = x + (i % 2) * (half + 2), tw = i % 2 == 0 ? half : w - half - 2;
            addButton(id, tx, themeY + (i / 2) * (themeHeight + 2), tw, themeHeight, t("theme." + id),
                    Style.ROW, prefs.theme().equals(id), () -> {
                        prefs.setTheme(id);
                        refresh(id);
                    });
        }

        int listY = bodyY + sectionHeight + 1;
        double scroll = players == null ? 0 : players.getScrollAmount();
        players = new OnlineVoiceList(minecraft, rightX + 1, listY, rightWidth - 2,
                Math.max(22, footerY - listY - 5), prefs, speaking, colors);
        players.setScrollAmount(scroll);
        add("players", players);
        int doneWidth = Math.min(72, rightWidth);
        addButton("done", rightX + rightWidth - doneWidth, footerY, doneWidth, 17, CommonComponents.GUI_DONE,
                Style.PRIMARY, false, this::onClose);
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

    private void addMobCalls(int x, int y, int w) {
        boolean available = changeMobCalls != null && mobCalls != null && minecraft.getConnection() != null;
        var detail = available ? (mobCalls.getAsBoolean() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF)
                : t("needs_world");
        var calls = rowButton("calls", x, y, w, t("calls", detail), t("calls.label"), detail, () -> {
            changeMobCalls.accept(!mobCalls.getAsBoolean());
            refresh("calls");
        });
        calls.active = available;
        calls.setTooltip(Tooltip.create(available ? t("calls.hint") : t("needs_world")));
    }

    private void refresh(String key) {
        focusAfterRefresh = key;
        rebuildWidgets();
        setFocused(controls.get(key));
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        boolean handled = super.mouseClicked(x, y, button);
        // Vanilla assigns focus after onPress returns, even if that press rebuilt the widgets.
        if (handled && getFocused() != null && !children().contains(getFocused())) {
            setFocused(controls.get(focusAfterRefresh));
        }
        return handled;
    }

    @Override public void tick() {
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
        return add(key, new VoiceWidgets.Button(x, y, w, rowHeight, message, colors,
                Style.ROW, false, label, detail, action));
    }

    private <T extends AbstractWidget> T add(String key, T widget) {
        controls.put(key, widget);
        return addRenderableWidget(widget);
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level == null) renderPanorama(g, partialTick);
        int right = left + panelWidth, bottom = top + panelHeight;
        g.fillGradient(0, 0, width, height, colors.backdropTop(), colors.backdropBottom());
        g.fill(left + 2, top + 3, right + 2, bottom + 3, 0x89000000);
        Screen.renderMenuBackgroundTexture(g, MENU_BACKGROUND, left, top, 0, 0, panelWidth, panelHeight);
        g.fill(left, top, right, bottom, 0xc0000000 | (colors.panel() & 0xffffff));
        g.renderOutline(left, top, panelWidth, panelHeight, colors.line());
        g.fillGradient(left + 1, top + 1, right - 1, bodyY - 3, colors.header(), colors.panel());
        g.fill(left + 1, top + 1, right - 1, top + 2, colors.accent());
        int titleY = top + (bodyY - top - 9) / 2;
        g.drawCenteredString(font, getTitle().copy().withStyle(ChatFormatting.BOLD), left + panelWidth / 2,
                titleY, colors.text());
        section(g, leftX, bodyY, leftWidth, appearanceY - 2, t("audio"));
        section(g, leftX, appearanceY, leftWidth, footerY - 4, t("appearance"));
        section(g, rightX, bodyY, rightWidth, footerY - 4, t("players"));
    }

    private void section(GuiGraphics g, int x, int y, int w, int bottom, Component title) {
        Screen.renderMenuBackgroundTexture(g, MENU_BACKGROUND, x, y, 0, 0, w, bottom - y);
        g.fill(x, y, x + w, bottom, 0xdb000000 | (colors.panel() & 0xffffff));
        g.renderOutline(x, y, w, bottom - y, colors.line());
        g.fillGradient(x + 1, y + 1, x + w - 1, y + sectionHeight, colors.header(), colors.control());
        g.fill(x + 1, y + sectionHeight - 1, x + w - 1, y + sectionHeight, colors.line());
        VoiceWidgets.text(g, font, title.copy().withStyle(ChatFormatting.BOLD), x + 5, x + w - 4,
                y + (sectionHeight - 9) / 2, colors.text(), false);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void removed() {
        if (!prefs.save()) SystemToast.add(minecraft.getToasts(), SAVE_ERROR, getTitle(), t("save_error"));
    }
}

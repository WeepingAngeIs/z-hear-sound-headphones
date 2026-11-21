package dev.mobsear.client;

import dev.mobsear.client.VoiceWidgets.Palette;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/** The right pane owns hit testing, focus, clipping and scrollbar dragging through Minecraft's list widget. */
final class OnlineVoiceList extends ContainerObjectSelectionList<OnlineVoiceList.Row> {
    private final VoicePreferences prefs;
    private final Predicate<UUID> speaking;
    private final Palette colors;
    private double targetScroll;
    private long lastFrame;

    OnlineVoiceList(Minecraft mc, int x, int y, int w, int h, VoicePreferences prefs, Predicate<UUID> speaking, Palette colors) {
        super(mc, w, h, y, h < 128 ? 33 : 41);
        setX(x);
        this.prefs = prefs;
        this.speaking = speaking;
        this.colors = colors;
        refreshRoster();
    }

    void refreshRoster() {
        var connection = minecraft.getConnection();
        List<PlayerInfo> players = connection == null ? List.of() : connection.getListedOnlinePlayers().stream()
                .filter(p -> !minecraft.isLocalPlayer(p.getProfile().getId()))
                .sorted(Comparator.comparing((PlayerInfo p) -> p.getProfile().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(p -> p.getProfile().getId())).toList();
        if (players.size() == children().size()) {
            boolean same = true;
            for (int i = 0; i < players.size(); i++) {
                if (players.get(i) != children().get(i).player) { same = false; break; }
            }
            if (same) return;
        }
        double scroll = getScrollAmount();
        var oldRows = new HashMap<UUID, Row>();
        for (Row row : children()) oldRows.put(row.id, row);
        var previousFocus = getFocused();
        var rows = players.stream().map(p -> {
            var row = oldRows.get(p.getProfile().getId());
            return row != null && row.player == p ? row : new Row(p);
        }).toList();
        if (previousFocus != null && !rows.contains(previousFocus)) setFocused(null);
        replaceEntries(rows);
        setScrollAmount(scroll);
    }

    @Override public int getRowWidth() { return Math.max(12, width - 18); }
    @Override public int getRowLeft() { return getX() + 6; }
    @Override protected int getScrollbarPosition() { return getRight() - 7; }
    @Override protected void renderListBackground(GuiGraphics g) {}
    @Override protected void renderListSeparators(GuiGraphics g) {}

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!isMouseOver(x, y) || getMaxScroll() == 0 || dy == 0) return false;
        targetScroll = Math.clamp(targetScroll - dy * itemHeight * 0.9, 0, getMaxScroll());
        super.setScrollAmount(getScrollAmount() + (targetScroll - getScrollAmount()) * 0.25);
        return true;
    }

    @Override public void setScrollAmount(double offset) {
        super.setScrollAmount(offset);
        targetScroll = getScrollAmount();
    }

    @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrame) * 1e-9);
        lastFrame = now;
        double diff = targetScroll - getScrollAmount();
        super.setScrollAmount(Math.abs(diff) < 0.1 ? targetScroll : getScrollAmount() + diff * (1 - Math.exp(-18 * dt)));
        super.renderWidget(g, mx, my, partial);
        if (children().isEmpty()) {
            g.drawWordWrap(minecraft.font, VoiceSettingsScreen.t("players.empty"), getX() + 7, getY() + 7,
                    Math.max(10, width - 15), colors.muted());
        }
    }

    @Override public void updateWidgetNarration(NarrationElementOutput out) {
        super.updateWidgetNarration(out);
        if (children().isEmpty()) out.add(NarratedElementType.HINT, VoiceSettingsScreen.t("players.empty"));
        else if (getFocused() != null) out.add(NarratedElementType.HINT, getFocused().status());
    }

    final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final PlayerInfo player;
        private final UUID id;
        private final VoiceWidgets.Slider gain;

        Row(PlayerInfo p) {
            player = p;
            id = p.getProfile().getId();
            var name = Component.literal(p.getProfile().getName());
            gain = new VoiceWidgets.Slider(0, 0, 100, 21, name, prefs.gain(id), colors,
                    n -> VoiceSettingsScreen.t("gain", name, n), n -> prefs.setGain(id, n));
        }

        Component status() { return VoiceSettingsScreen.t(speaking.test(id) ? "speaking" : "quiet"); }
        @Override public List<? extends GuiEventListener> children() { return List.of(gain); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(gain); }

        @Override public void render(GuiGraphics g, int index, int y, int x, int w, int h, int mx, int my,
                                     boolean hovered, float partial) {
            int face = h < 30 ? 14 : 20;
            int faceX = x + 3, faceY = y + (h - face) / 2;
            g.fill(x, y, x + w, y + h, colors.control());
            g.renderOutline(x, y, w, h, hovered || gain.isFocused() ? colors.line() : colors.header());
            PlayerFaceRenderer.draw(g, player.getSkin(), faceX, faceY, face);
            int sx = faceX + face + 3;
            gain.setRectangle(Math.max(12, x + w - sx - 2), h - 11, sx, y + 1);
            gain.render(g, mx, my, partial);
            boolean activeVoice = speaking.test(id);
            int sy = y + h - 10;
            g.fill(sx + 4, sy + 3, sx + 7, sy + 6, activeVoice ? colors.accent() : colors.line());
            VoiceWidgets.text(g, minecraft.font, status(), sx + 10, x + w - 3, sy,
                    activeVoice ? colors.text() : colors.muted(), false);
        }
    }
}

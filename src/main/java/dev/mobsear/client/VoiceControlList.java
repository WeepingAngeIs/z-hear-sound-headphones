package dev.mobsear.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

final class VoiceControlList extends ContainerObjectSelectionList<VoiceControlList.Row> {
    VoiceControlList(Minecraft mc, int x, int y, int width, int height, int rowHeight) {
        super(mc, width, height, y, rowHeight + 2);
        setX(x);
    }

    void put(AbstractWidget widget) { addEntry(new Row(widget)); }
    void focus(AbstractWidget widget) {
        for (Row row : children()) {
            if (row.widget == widget) {
                setFocused(row);
                row.setFocused(widget);
                break;
            }
        }
    }
    @Override public int getRowWidth() { return Math.max(12, width - 12); }
    @Override public int getRowLeft() { return getX() + 2; }
    @Override protected int getScrollbarPosition() { return getRight() - 7; }
    @Override protected void renderListBackground(GuiGraphics g) {}
    @Override protected void renderListSeparators(GuiGraphics g) {}

    final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final AbstractWidget widget;
        Row(AbstractWidget widget) { this.widget = widget; }
        @Override public List<? extends GuiEventListener> children() { return List.of(widget); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(widget); }
        @Override public void render(GuiGraphics g, int index, int y, int x, int width, int height,
                                     int mx, int my, boolean hovered, float partial) {
            widget.setRectangle(width, itemHeight - 2, x, y);
            widget.render(g, mx, my, partial);
        }
    }
}

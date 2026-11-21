package dev.mobsear.client;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class VoiceWidgets {
    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private VoiceWidgets() {}

    record Palette(int backdropTop, int backdropBottom, int panel, int header, int line,
                   int control, int hover, int primary, int accent, int focus, int text, int muted) {
        static final Palette UWU = new Palette(0x6632172d, 0x98180d17, 0xff60304b, 0xffa34a71,
                0xffbd829e, 0xff813a59, 0xff964c6c, 0xff994869, 0xffffa7d0, 0xffffe2f0, 0xfffff5fa, 0xffffe1ee);
        static final Palette EMO = new Palette(0x70111219, 0xa414101e, 0xff16191e, 0xff30313d,
                0xff626371, 0xff292c33, 0xff3b3e4b, 0xff454255, 0xffb7b1cf, 0xfff3eaff, 0xfff6f2ff, 0xffcbc9d6);
        static final Palette VANILLA = new Palette(0x6630281b, 0x971f1c18, 0xff454938, 0xff6c654d,
                0xffa39d81, 0xff625f4b, 0xff716b55, 0xff716345, 0xffe5cf8e, 0xfffff1ce, 0xfffff9ee, 0xfff6edd7);
        static final Palette AMETHYST = new Palette(0x75281b45, 0xa2221339, 0xff34245c, 0xff644785,
                0xff9b81ba, 0xff504277, 0xff685189, 0xff74518f, 0xffd8adff, 0xffffe4ff, 0xfffbf4ff, 0xffecd6fb);

        static Palette forTheme(String id) {
            return switch (id) {
                case "emo" -> EMO;
                case "vanilla" -> VANILLA;
                case "amethyst" -> AMETHYST;
                default -> UWU;
            };
        }
    }

    enum Style { ROW, PRIMARY }

    static final class Button extends AbstractButton {
        private final Palette colors;
        private final Style style;
        private final boolean selected;
        private final Runnable press;
        private final Component label;
        private final Component detail;

        Button(int x, int y, int width, int height, Component message, Palette colors, Style style,
               boolean selected, Component label, Component detail, Runnable press) {
            super(x, y, width, height, message);
            this.colors = colors;
            this.style = style;
            this.selected = selected;
            this.press = press;
            this.label = label;
            this.detail = detail;
        }

        @Override public void onPress() { press.run(); }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getX(), y = getY(), right = x + getWidth(), bottom = y + getHeight();
            boolean hot = active && isHoveredOrFocused();
            int fill = hot ? colors.hover : style == Style.PRIMARY || selected ? colors.primary : colors.control;
            g.blitSprite(BUTTON, x, y, getWidth(), getHeight());
            g.fill(x + 1, y + 1, right - 1, bottom - 1, 0xb3000000 | (fill & 0x00ffffff));
            if (selected) g.fill(x + 1, bottom - 2, right - 1, bottom, colors.accent);
            if (isFocused()) g.renderOutline(x, y, getWidth(), getHeight(), colors.focus);
            int ink = active ? colors.text : colors.muted;
            if (detail == null) {
                renderScrollingString(g, font, 5, ink);
                return;
            }
            int gutter = 8;
            int middle = x + Math.min(getWidth() / 2, font.width(label) + gutter * 2);
            text(g, font, label, x + gutter, middle - 2, y + (getHeight() - 9) / 2, ink, false);
            text(g, font, detail, middle + 2, right - gutter, y + (getHeight() - 9) / 2, colors.muted, true);
        }

        @Override public void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
            if (selected) out.add(NarratedElementType.HINT, Component.translatable("mobsear.settings.selected"));
        }
    }

    static final class Slider extends AbstractSliderButton {
        private final Component label;
        private final Palette colors;
        private final IntFunction<Component> message;
        private final IntConsumer change;

        Slider(int x, int y, int width, int height, Component label, int initial,
               Palette colors, IntFunction<Component> message, IntConsumer change) {
            super(x, y, width, height, message.apply(initial), initial / 200.0);
            this.label = label;
            this.colors = colors;
            this.message = message;
            this.change = change;
        }

        private int percent() { return (int) Math.round(value * 200); }
        @Override protected void updateMessage() { setMessage(message.apply(percent())); }
        @Override protected void applyValue() { change.accept(percent()); }

        @Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getX(), y = getY(), end = x + getWidth(), bottom = y + getHeight();
            g.fill(x, y, end, bottom, isHoveredOrFocused() ? colors.hover : colors.control);
            int rail = bottom - 5;
            int handle = x + 4 + (int) Math.round((getWidth() - 8) * value);
            g.fill(x + 4, rail, end - 4, rail + 2, colors.line);
            g.fill(x + 4, rail, handle, rail + 2, colors.accent);
            g.fill(handle - 2, rail - 1, handle + 2, rail + 3, colors.focus);
            if (isFocused()) g.renderOutline(x, y, getWidth(), getHeight(), colors.focus);
            Component number = Component.translatable("mobsear.settings.percent", percent());
            int ty = y + Math.max(1, (getHeight() - 15) / 2);
            text(g, font, label, x + 8, end - 18 - font.width(number), ty, colors.text, false);
            text(g, font, number, x + 8, end - 8, ty, colors.muted, true);
        }
    }

    /** Minecraft scrolls long values inside their own field; narration retains the complete label. */
    static void text(GuiGraphics g, Font font, Component value, int left, int right, int y, int color, boolean alignRight) {
        if (right <= left) return;
        int width = font.width(value);
        if (width > right - left) {
            AbstractWidget.renderScrollingString(g, font, value, left, y - 1, right, y + 10, color);
        } else {
            g.drawString(font, value, alignRight ? right - width : left, y, color, false);
        }
    }
}

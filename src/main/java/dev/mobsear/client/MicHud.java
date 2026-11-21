package dev.mobsear.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mobsear.net.MicState;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;

/** Native 22-pixel atlas cells for both the HUD and the vanilla name-tag font. */
@Mod(value = "mobsear", dist = Dist.CLIENT)
public final class MicHud {
    private static final List<String> FAMILIES = List.of("uwu", "emo", "vanilla", "amethyst");
    private static final List<String> ACCENTS = List.of("auto", "rose", "gold", "mint", "aqua", "violet");
    private static final int CELL = 22, FRAMES = 13;
    private static final List<ResourceLocation> TEXTURES = FAMILIES.stream()
            .map(id -> ResourceLocation.fromNamespaceAndPath("mobsear", "textures/font/mic_" + id + ".png")).toList();
    private static final Style GLYPH = Style.EMPTY
            .withFont(ResourceLocation.fromNamespaceAndPath("mobsear", "microphones"))
            .withColor(0xffffff).withBold(false).withItalic(false).withUnderlined(false)
            .withStrikethrough(false).withObfuscated(false);
    private static final Component[][] TAGS = new Component[FAMILIES.size()][FRAMES];
    static {
        for (int family = 0; family < FAMILIES.size(); family++) {
            for (int frame = 0; frame < FRAMES; frame++) {
                char glyph = (char) (0xe100 + family * ACCENTS.size() * FRAMES + frame);
                TAGS[family][frame] = Component.literal(String.valueOf(glyph)).setStyle(GLYPH);
            }
        }
    }

    public MicHud() {
        NeoForge.EVENT_BUS.addListener(this::overlay);
        NeoForge.EVENT_BUS.addListener(this::nameTag);
    }

    private void overlay(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || !VoiceClient.connected()) return;
        var prefs = VoiceClient.options();
        if (!prefs.hudVisible()) return;
        int family = family(VoiceClient.localStyle());
        int accent = Math.max(0, ACCENTS.indexOf(prefs.accent()));
        int frame = frame(VoiceClient.localState(), VoiceClient.inputLevel(), prefs.effects());
        var graphics = event.getGuiGraphics();
        // Rise above the hotbar in narrow GUI layouts as well as ordinary widescreen layouts.
        int y = graphics.guiHeight() - CELL - (graphics.guiWidth() < 232 ? 29 : 7);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(TEXTURES.get(family), 7, y, frame * CELL, accent * CELL, CELL, CELL,
                FRAMES * CELL, ACCENTS.size() * CELL);
        RenderSystem.disableBlend();
    }

    private void nameTag(RenderNameTagEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !VoiceClient.connected() || !VoiceClient.options().nameplates()
                || event.canRender() == TriState.FALSE || !(event.getEntity() instanceof AbstractClientPlayer player)
                || player == mc.player || player.isInvisibleTo(mc.player) || player.distanceToSqr(mc.player) >= 32 * 32) return;
        var id = player.getUUID();
        MicState state = VoiceClient.remoteState(id);
        if (state == null) return;
        int frame = frame(state, VoiceClient.remoteLevel(id), VoiceClient.options().effects());
        var glyph = TAGS[family(VoiceClient.remoteStyle(id))][frame];
        // Appending to the existing component leaves vanilla distance, teams, sneaking and occlusion intact.
        event.setContent(event.getContent().copy().append(" ").append(glyph));
    }

    private static int family(String name) { return Math.max(0, FAMILIES.indexOf(name)); }

    private static int frame(MicState state, double level, String effects) {
        return switch (state) {
            case IDLE -> 0;
            case MUTED -> 1;
            case DEAFENED -> 2;
            case SPEAKING -> {
                boolean loud = level >= 0.25;
                if (!effects.equals("full")) yield effects.equals("reduced") && loud ? 4 : 3;
                int phase = (int) Math.floorMod(System.nanoTime() / 150_000_000L, 4);
                yield (loud ? 9 : 5) + phase;
            }
        };
    }
}

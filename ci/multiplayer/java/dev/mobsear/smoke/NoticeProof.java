package dev.mobsear.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mobsear.client.NoticeFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Inspect the final framebuffer, including real Minecraft heart and armor sprites. */
final class NoticeProof {
    static final int COUNT = 6;
    static final Component HEARD = Component.translatable("mobsear.speech.heard", Component.translatable("entity.minecraft.enderman"));
    private int requested = -1, completed = -1, frames;

    NoticeProof() { NeoForge.EVENT_BUS.addListener(this::render); }

    private static int scale(int view) { return 1 + view / 2; }
    private static boolean armored(int view) { return view % 2 != 0; }

    static void equip(ServerPlayer player, int view) {
        player.setItemSlot(EquipmentSlot.CHEST, armored(view) ? new ItemStack(Items.DIAMOND_CHESTPLATE) : ItemStack.EMPTY);
        player.setHealth(player.getMaxHealth());
    }

    static void prepare(Minecraft mc, int view) {
        if (mc.getWindow().getWidth() != 960 || mc.getWindow().getHeight() != 720) mc.getWindow().setWindowed(960, 720);
        mc.options.guiScale().set(scale(view));
    }

    static boolean ready(Minecraft mc, int view) {
        return mc.getWindow().getGuiScale() == scale(view)
                && mc.getWindow().getWidth() == 960 && mc.getWindow().getHeight() == 720
                && (mc.player.getArmorValue() > 0) == armored(view);
    }

    boolean captured(int view) {
        if (requested != view) { requested = view; frames = 0; }
        return completed == view;
    }

    private void render(RenderFrameEvent.Post event) {
        if (requested < 0 || requested == completed) return;
        var mc = Minecraft.getInstance();
        if (mc.screen != null || !ready(mc, requested) || !HEARD.getString().equals(NoticeFixture.text()) || ++frames < 3) return;
        int view = requested, s = scale(view);
        if (!NoticeFixture.localMatch().isEmpty()) throw SmokeControl.failure("Debug text active in a normal notice screenshot");
        if (view == 0) {
            // This unrelated native action-bar message was present when the recognized call was sent.
            try {
                var field = Gui.class.getDeclaredField("overlayMessageString");
                field.setAccessible(true);
                if (!(field.get(mc.gui) instanceof Component message) || !"Quest updated".equals(message.getString()))
                    throw SmokeControl.failure("Mob call replaced the vanilla action bar");
            } catch (ReflectiveOperationException e) { throw SmokeControl.failure("Cannot inspect the native action bar: " + e); }
        }
        String name = "notice-scale-" + s + (armored(view) ? "-armor" : "-hearts");
        var folder = Path.of(System.getenv("MOBSEAR_SMOKE_DIR"), "notices");
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
             var front = sprite(mc, armored(view) ? "armor_full" : "heart/full");
             var back = sprite(mc, armored(view) ? "armor_full" : "heart/container")) {
            Files.createDirectories(folder);
            image.writeToFile(folder.resolve(name + ".png"));
            int width = image.getWidth() / s, height = image.getHeight() / s, left = width / 2 - 91;
            int top = -1, railBottom = -1;
            for (int y = height - 100; y < height - 25; y++) {
                if (top < 0 && matchesHudSprite(image, front, back, left, y, s)) top = y;
                int railPixels = 0;
                for (int x = left - 2; x < Math.min(width, left + 200); x++) {
                    int color = image.getPixelRGBA(x * s + s / 2, y * s + s / 2);
                    // The custom frame uses opaque pink pixels, independent of world lighting.
                    if ((color & 0xffffff) == 0xa177d7) railPixels++;
                }
                if (railPixels >= 5) railBottom = y;
            }
            int gap = top - railBottom - 1;
            System.out.printf("MOBSEAR_NOTICE_FRAME %s viewport=%dx%d armor=%d noticeBottom=%d hudTop=%d gap=%d visible=%s%n",
                    name, image.getWidth(), image.getHeight(), mc.player.getArmorValue(), railBottom, top, gap, NoticeFixture.text());
            if (top < 0 || railBottom < 0 || gap < 1 || gap > 4)
                throw SmokeControl.failure("Missing or misplaced notice/vanilla sprite: " + name + " rail=" + railBottom + " stats=" + top + " gap=" + gap);
            completed = view;
        } catch (IOException e) { throw SmokeControl.failure("Cannot capture " + name + ": " + e); }
    }

    private static NativeImage sprite(Minecraft mc, String name) throws IOException {
        var id = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/sprites/hud/" + name + ".png");
        try (var stream = mc.getResourceManager().open(id)) { return NativeImage.read(stream); }
    }

    private static boolean matchesHudSprite(NativeImage frame, NativeImage front, NativeImage back, int left, int top, int s) {
        // The neighboring health icon covers the ninth column. Every opaque pixel in the other eight
        // columns must match the player's actual full armor/heart sprite, including its top black outline.
        for (int y = 0; y < 9; y++) for (int x = 0; x < 8; x++) {
            int expected = front.getPixelRGBA(x, y);
            if (expected >>> 24 == 0) expected = back.getPixelRGBA(x, y);
            if (expected >>> 24 == 0) continue;
            int actual = frame.getPixelRGBA((left + x) * s + s / 2, (top + y) * s + s / 2);
            if (((actual ^ expected) & 0xffffff) != 0) return false;
        }
        return true;
    }
}

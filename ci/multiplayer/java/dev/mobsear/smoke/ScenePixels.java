package dev.mobsear.smoke;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Compare actual menu-body movement to the resource's RGB deltas and alpha. Excludes the header preview. */
final class ScenePixels {
    private ScenePixels() {}

    static void checkBody(Path dir, String id) throws IOException {
        var texture = ResourceLocation.fromNamespaceAndPath("mobsear", "textures/gui/themes/" + id + ".png");
        int[] atlas;
        try (var stream = Minecraft.getInstance().getResourceManager().open(texture)) {
            atlas = pixels(ImageIO.read(stream));
        }
        String prefix = "menu-" + id + "-frame-";
        int[] first = pixels(ImageIO.read(dir.resolve(prefix + "0.png").toFile()));
        int matches = 0;
        for (int phase = 1; phase < 8; phase++) {
            int[] actual = pixels(ImageIO.read(dir.resolve(prefix + phase + ".png").toFile()));
            // In this 854×480 fixture the full 192×96 art covers [50,802) × [34,446).
            for (int y = 78; y < 444; y++) {
                int ty = (2 * (y - 34) + 1) * 96 / (2 * 412);
                for (int x = 52; x < 800; x++) {
                    int tx = (2 * (x - 50) + 1) * 192 / (2 * 752);
                    int offset = ty * 192 + tx;
                    int initial = atlas[offset], next = atlas[phase * 192 * 96 + offset];
                    if (next == initial) continue;
                    int pos = y * 854 + x;
                    if (matchesBlend(initial, next, first[pos], actual[pos])) matches++;
                }
            }
        }
        if (matches < 8) throw SmokeControl.failure(id + " scene motion did not reach the main menu panel: " + matches);
        System.out.println("MOBSEAR_SCENE_BODY " + id + " matched=" + matches);
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static boolean matchesBlend(int before, int after, int first, int actual) {
        // Section fills have alpha 0x52. Gaps expose the scene directly. Widgets may cover it completely.
        int signal = 0, directError = 0, sectionError = 0;
        double alpha = (after >>> 24) / 255.0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int delta = (after >> shift & 255) - (before >> shift & 255);
            int observed = (actual >> shift & 255) - (first >> shift & 255);
            signal = Math.max(signal, Math.abs(delta));
            directError = Math.max(directError, Math.abs(observed - (int) Math.round(alpha * delta)));
            sectionError = Math.max(sectionError, Math.abs(observed - (int) Math.round(alpha * (1 - 82 / 255.0) * delta)));
        }
        return signal >= 17 && Math.min(directError, sectionError) <= 3;
    }
}

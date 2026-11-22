package dev.mobsear.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.MenuTheme;
import dev.mobsear.client.PitchSettingsScreen;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.client.VoicePreferences;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.Util;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Runs after the four network phases. Closing the menu allows the final near frame to be sent. */
final class MenuSmoke {
    private enum Step {
        FOCUS, OPEN, SCROLL_DOWN, SCROLL_UP, SELECT_THEME, CAPTURE, ANIMATION, REDUCED, CLOSE, REOPEN,
        AUDIO_MODE, RESTORE_MODE, COMPACT, RESTORE_SIZE, GUI_SCALE3, PITCH, PITCH_CAPTURE, PITCH_BACK, DONE
    }
    private static final String[] THEMES = {"UwU", "Dark / Emo", "Vanilla", "Amethyst"};
    private static final MenuTheme[] BACKDROPS = captureOrder();
    private static MenuTheme[] captureOrder() {
        var choices = new java.util.ArrayList<MenuTheme>();
        for (MenuTheme base : MenuTheme.values()) {
            if (base.hasScene()) continue;
            var current = base;
            do {
                current = current.next();
                choices.add(current);
            } while (current != base);
        }
        return choices.toArray(MenuTheme[]::new);
    }
    private static final UUID SPEAKER_ID = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + SmokeControl.SPEAKER).getBytes(StandardCharsets.UTF_8));
    private final Minecraft mc = Minecraft.getInstance();
    private final ClientPacketListener connection = mc.getConnection();
    private final Runnable acknowledge;
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(250);
    private final Path screenshots = mc.gameDirectory.toPath().resolve("screenshots");
    private Step step = Step.FOCUS;
    private int theme;
    private int renderedFrames;
    private boolean displayed;
    private int fixedX, fixedY;
    private int framesSeen;
    private long frameStartSlot = -1;
    private long reducedAt;
    private Screen pitchParent;
    private static final boolean PUBLIC_STACK = "1".equals(System.getenv("MOBSEAR_PUBLIC_MENU_STACK"));
    private boolean checkedScale3;

    MenuSmoke(Runnable acknowledge) {
        this.acknowledge = acknowledge;
        NeoForge.EVENT_BUS.addListener(this::beginFrame);
        NeoForge.EVENT_BUS.addListener(this::render);
        GLFW.glfwFocusWindow(mc.getWindow().getWindow());
    }

    void tick() {
        if (step == Step.DONE) return;
        // A fresh offline fixture shows safety and advancement toasts over the header ornament.
        mc.getToasts().clear();
        if (System.nanoTime() - deadline > 0) {
            throw SmokeControl.failure("menu timed out in " + step + ", active=" + mc.isWindowActive()
                    + ", screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()));
        }
        switch (step) {
            case FOCUS -> {
                if (mc.screen instanceof PauseScreen pause) pause.onClose();
                if (mc.screen == null && mc.isWindowActive()) {
                    pressG();
                    step = Step.OPEN;
                }
            }
            case OPEN -> {
                if (!inSettings() || !displayed) return;
                if (PUBLIC_STACK) {
                    for (String id : java.util.List.of("fancymenu", "konkrete", "melody", "drippyloadingscreen", "sodium", "iris"))
                        check(net.neoforged.fml.ModList.get().isLoaded(id), "public compatibility client did not load " + id);
                    System.out.println("MOBSEAR_PUBLIC_MENU_STACK loaded FancyMenu Konkrete Melody DrippyLoadingScreen Sodium Iris");
                }
                slider("Voice volume", 0.25);
                check(value("Voice volume", 50), "master slider did not update");
                slider(SmokeControl.SPEAKER, 0.75);
                check(value(SmokeControl.SPEAKER, 150), "player slider did not update");
                var master = widget("Voice volume", true);
                fixedX = master.getX();
                fixedY = master.getY();
                var list = roster();
                check(list.getScrollAmount() == 0 && visibleRow(list, SmokeControl.SPEAKER)
                                && !visibleRow(list, SmokeControl.guestName(SmokeControl.LISTED_GUESTS)),
                        "roster must start with the real peer and overflow the viewport");
                wheel(list, -100);
                step = Step.SCROLL_DOWN;
            }
            case SCROLL_DOWN -> {
                var list = roster();
                if (!visibleRow(list, SmokeControl.guestName(SmokeControl.LISTED_GUESTS))) return;
                check(list.getScrollAmount() > 0 && !visibleRow(list, SmokeControl.SPEAKER), "roster did not scroll");
                var master = widget("Voice volume", true);
                check(master.getX() == fixedX && master.getY() == fixedY && value("Voice volume", 50),
                        "left pane moved or changed");
                wheel(list, 100);
                step = Step.SCROLL_UP;
            }
            case SCROLL_UP -> {
                var list = roster();
                if (list.getScrollAmount() != 0) return;
                check(visibleRow(list, SmokeControl.SPEAKER), "roster did not return to the real peer");
                step = Step.SELECT_THEME;
            }
            case SELECT_THEME -> {
                MenuTheme selected = BACKDROPS[theme];
                var choice = theme % 5 == 0 ? widget(THEMES[theme / 5], false) : widget("Backdrop", true);
                if (theme == 5) keyboardSelect(choice);
                else if (choice.active) pointer(choice, 0.5);
                check(mc.getConnection() == connection && value("Voice volume", 50) && value(SmokeControl.SPEAKER, 150),
                        "connection or gain changed after applying " + selected.id());
                check(VoiceClient.options().theme().equals(selected.id()), "wrong backdrop selected: " + selected.id());
                step = Step.CAPTURE;
            }
            case CLOSE -> {
                // Keep the final Starfield selected for the independent pitch persistence/restart fixtures.
                for (int i = 0; i < 4; i++) pointer(widget("Backdrop", true), .5);
                check(VoiceClient.options().theme().equals("amethyst_stars"), "could not select Starfield after Original");
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                check(mc.screen == null, "Escape did not return to the game");
                pressG();
                step = Step.REOPEN;
            }
            case REOPEN -> {
                if (!inSettings()) return;
                check(value("Voice volume", 50), "reopened master value changed");
                check(value(SmokeControl.SPEAKER, 150), "reopened player value changed");
                var saved = new VoicePreferences(FMLPaths.CONFIGDIR.get().resolve("mobsear-client.properties"));
                check(saved.volume() == 50 && saved.gain(SPEAKER_ID) == 150
                        && saved.theme().equals("amethyst_stars"), "saved preferences did not reload");
                compareThemes();
                step = Step.AUDIO_MODE;
            }
            case AUDIO_MODE, RESTORE_MODE -> {
                var audio = list("VoiceControlList");
                var mode = widget("Input mode", true);
                if (!visibleRow(audio, "Input mode")) {
                    wheel(audio, -1);
                    return;
                }
                if (mode.getY() == 0) return; // The just-rebuilt native row is positioned during render.
                if (mode.getY() < audio.getY() || mode.getBottom() > audio.getBottom()) {
                    wheel(audio, mode.getY() < audio.getY() ? 1 : -1);
                    return;
                }
                boolean enabling = step == Step.AUDIO_MODE;
                pointer(mode, .5);
                check(VoiceClient.voiceActivated() == enabling && roster().getScrollAmount() == 0
                                && value(SmokeControl.SPEAKER, 150), "audio toggle changed the right pane or missed input");
                if (enabling) step = Step.RESTORE_MODE;
                else {
                    mc.getWindow().setWindowed(640, 480);
                    step = Step.COMPACT;
                    renderedFrames = 0;
                }
            }
            case RESTORE_SIZE -> {
                if (mc.getWindow().getWidth() != 854 || mc.screen.width != 427) return;
                if (PUBLIC_STACK && !checkedScale3) {
                    mc.getWindow().setWindowed(1280, 720);
                    renderedFrames = 0;
                    step = Step.GUI_SCALE3;
                    return;
                }
                pitchParent = mc.screen;
                pointer(widget("Voice Changer", false), .5);
                step = Step.PITCH;
            }
            case PITCH -> {
                check(mc.screen instanceof PitchSettingsScreen && mc.screen.getTitle().getString().equals("Voice Changer"),
                        "missing native Voice Changer submenu");
                var prefs = VoiceClient.options();
                check(prefs.reverbAmount()==0, "Reverb must start off");
                check(!prefs.pitchEnabled() && prefs.pitchCents() == 0 && prefs.pitchPreset().equals("natural"),
                        "unexpected initial pitch preferences");
                pointer(widget("Deep", false), .5);
                check(prefs.pitchEnabled() && prefs.pitchCents() == VoicePreferences.DEEP_CENTS, "Deep preset");
                pointer(widget("Pitch", true), .5);
                check(prefs.pitchCents() == 0, "pitch slider midpoint");
                pointer(widget("Pitch", true), .625);
                check(prefs.pitchCents() == 300 && prefs.pitchPreset().equals("custom"),
                        "manual pitch did not select Custom at a quarter octave");
                pointer(widget("Squeaky", false), .5);
                pointer(widget("Reverb", true), .75);
                check(prefs.reverbAmount()==75 && prefs.pitchPreset().equals("squeaky"), "Reverb changed the pitch preset");
                pointer(widget("Voice effect", true), .5);
                check(!prefs.pitchEnabled() && prefs.pitchCents() == VoicePreferences.SQUEAKY_CENTS,
                        "bypass discarded preset");
                pointer(widget("Voice effect", true), .5);
                check(prefs.pitchEnabled() && prefs.pitchCents() == VoicePreferences.SQUEAKY_CENTS,
                        "could not restore selected pitch");
                renderedFrames = 0;
                step = Step.PITCH_CAPTURE;
            }
            case PITCH_BACK -> {
                pointer(widget("Back", false), .5);
                check(mc.screen == pitchParent, "Voice Changer Back lost the parent");
                pointer(widget("Voice Changer", false), .5);
                var prefs = VoiceClient.options();
                check(prefs.reverbAmount()==75, "Reopening lost the selected reverb amount");
                check(prefs.pitchEnabled() && prefs.pitchCents() == VoicePreferences.SQUEAKY_CENTS,
                        "reopened Voice Changer lost preset");
                pointer(widget("Custom", false), .5);
                check(prefs.pitchEnabled() && prefs.pitchCents() == 300, "Custom lost the manual slider value");
                pointer(widget("Voice effect", true), .5);
                pointer(widget("Back", false), .5);
                pointer(widget("Voice Changer", false), .5);
                check(!prefs.pitchEnabled() && prefs.pitchCents() == 300, "reopening lost the bypassed custom setting");
                pointer(widget("Squeaky", false), .5);
                pointer(widget("Voice effect", true), .5);
                pointer(widget("Back", false), .5);
                check(!prefs.pitchEnabled() && prefs.pitchCents() == VoicePreferences.SQUEAKY_CENTS
                                && prefs.pitchPreset().equals("squeaky") && prefs.customPitchCents() == 300,
                        "could not persist bypassed Squeaky selection");
                try {
                    Files.copy(FMLPaths.CONFIGDIR.get().resolve("mobsear-client.properties"),
                            Path.of(System.getenv("MOBSEAR_SMOKE_DIR"), "pitch-persistence.properties"));
                } catch (IOException ex) { throw SmokeControl.failure("cannot preserve real UI preferences: " + ex); }
                pointer(widget("Voice Changer", false), .5);
                pointer(widget("Reset to Defaults", false), .5);
                pointer(widget("Back", false), .5);
                check(mc.screen == pitchParent && prefs.theme().equals("amethyst_stars") && !prefs.pitchEnabled()
                                && prefs.pitchCents() == 0 && prefs.pitchPreset().equals("natural")
                                && prefs.customPitchCents() == 0 && prefs.reverbAmount()==0 && prefs.volume() == 50 && prefs.gain(SPEAKER_ID) == 150,
                        "pitch reset changed unrelated preferences or failed to restore natural bypass");
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                check(mc.screen == null && mc.getConnection() == connection, "voice session did not survive settings");
                step = Step.DONE;
                System.out.println("MOBSEAR_MENU_PASS hotkey keyboard sliders 20-backdrops persistence screenshots");
                acknowledge.run();
            }
            default -> { /* Screenshot states advance after a rendered frame. */ }
        }
    }

    private void beginFrame(RenderFrameEvent.Pre event) {
        frameStartSlot = step == Step.ANIMATION ? Util.getMillis() / 400L : -1;
    }

    private void render(RenderFrameEvent.Post event) {
        if (step == Step.PITCH_CAPTURE && mc.screen instanceof PitchSettingsScreen && ++renderedFrames >= 3) {
            save("menu-voice-changer.png");
            step = Step.PITCH_BACK;
            return;
        }
        if (!inSettings()) return;
        if (step == Step.OPEN && !displayed) {
            check(VoiceClient.options().theme().equals("uwu_lotus"), "fresh menu did not open on Lotus Pond");
            save("menu-first-open.png");
        }
        displayed = true;
        if (step == Step.GUI_SCALE3) {
            if (mc.getWindow().getWidth() != 1280) return;
            if (mc.getWindow().getGuiScale() != 3) { mc.options.guiScale().set(3); return; }
            if (++renderedFrames < 4) return;
            save("menu-gui-scale3.png");
            checkedScale3 = true;
            mc.options.guiScale().set(2);
            mc.getWindow().setWindowed(854, 480);
            step = Step.RESTORE_SIZE;
            return;
        }
        if (step == Step.COMPACT) {
            if (mc.screen.width != 320 || mc.screen.height != 240 || ++renderedFrames < 4) return;
            var audio = list("VoiceControlList");
            var people = roster();
            var peer = widget(SmokeControl.SPEAKER, true);
            check(audio.getRight() < people.getX() && peer.getX() >= people.getX() && peer.getRight() <= people.getRight()
                    && visibleRow(people, SmokeControl.SPEAKER), "compact controls overlap or clip the first peer");
            pointer(peer, .75);
            check(mc.screen.getFocused() == people && people.getFocused() != null && value(SmokeControl.SPEAKER, 150),
                    "compact roster could not take focus and preserve gain");
            save("menu-compact.png");
            mc.getWindow().setWindowed(854, 480);
            step = Step.RESTORE_SIZE;
            return;
        }
        if (step == Step.ANIMATION || step == Step.REDUCED) {
            long now = Util.getMillis();
            if (step == Step.ANIMATION) {
                // Only label a finished draw if the complete render stayed in one production time slot.
                if (frameStartSlot != now / 400L) return;
                int frame = (int)(frameStartSlot % 8);
                if (FixtureSound.sceneFrame() != frame) return;
                if ((framesSeen & (1 << frame)) == 0) {
                    save("menu-" + BACKDROPS[theme].id() + "-frame-" + frame + ".png");
                    framesSeen |= 1 << frame;
                }
                if (framesSeen == 255) {
                    checkScenePixels();
                    if (BACKDROPS[theme] == MenuTheme.LOTUS) {
                        VoiceClient.options().setEffects("reduced");
                        reducedAt = now;
                        step = Step.REDUCED;
                    } else nextTheme();
                }
            } else {
                check(FixtureSound.sceneFrame() == 0, "reduced motion did not freeze scene");
                if (framesSeen == 255 && now - reducedAt > 200) {
                    save("menu-uwu_lotus-reduced.png");
                    framesSeen++;
                } else if (framesSeen == 256 && now - reducedAt > 1900) {
                    save("menu-uwu_lotus-reduced-check.png");
                    try {
                        check(java.util.Arrays.equals(headerStamp("reduced"), headerStamp("reduced-check")),
                                "reduced motion changed the scene header pixels");
                    } catch (IOException ex) { throw SmokeControl.failure("could not verify the reduced scene"); }
                    VoiceClient.options().setEffects("full");
                    nextTheme();
                }
            }
            return;
        }
        if (step != Step.CAPTURE || ++renderedFrames < 3) return;
        renderedFrames = 0;
        save("menu-" + BACKDROPS[theme].id() + ".png");
        if (BACKDROPS[theme].hasScene()) {
            framesSeen = 0;
            step = Step.ANIMATION;
        }
        else nextTheme();
    }

    private void nextTheme() { step = ++theme < BACKDROPS.length ? Step.SELECT_THEME : Step.CLOSE; }

    private void save(String filename) {
        try (var picture = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int w = step == Step.GUI_SCALE3 ? 1280 : step == Step.COMPACT ? 640 : 854;
            check(picture.getWidth() == w && picture.getHeight() == (step == Step.GUI_SCALE3 ? 720 : 480),
                    "unexpected framebuffer dimensions");
            Files.createDirectories(screenshots);
            picture.writeToFile(screenshots.resolve(filename));
        } catch (IOException e) {
            throw SmokeControl.failure("could not capture " + filename + ": " + e.getMessage());
        }
    }

    private void checkScenePixels() {
        try {
            var sections = new java.util.ArrayList<int[]>();
            for (int i = 0; i < 8; i++) {
                int[] stamp = headerStamp("frame-" + i);
                check(sections.stream().noneMatch(previous -> java.util.Arrays.equals(previous, stamp)),
                        "two scenic phases rendered the same header stamp for " + BACKDROPS[theme].id());
                sections.add(stamp);
            }
            if (PUBLIC_STACK) ScenePixels.checkBody(screenshots, BACKDROPS[theme].id());
        } catch (IOException ex) { throw SmokeControl.failure("could not compare actual scene frames"); }
    }

    private int[] headerStamp(String suffix) throws IOException {
        var image = ImageIO.read(screenshots.resolve("menu-" + BACKDROPS[theme].id() + "-" + suffix + ".png").toFile());
        // 854x480, GUI scale 2, fixed 4-unit panel margin. Crop inside the native header ornament.
        return image.getRGB(738, 16, 96, 48, null, 0, 96);
    }

    private boolean inSettings() {
        return mc.screen != null && mc.screen.getClass().getName().equals("dev.mobsear.client.VoiceSettingsScreen");
    }

    static void pressG() {
        KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G));
    }

    private AbstractWidget widget(String name, boolean prefix) {
        var found = find(mc.screen, name, prefix);
        if (found == null) throw SmokeControl.failure("missing control " + name);
        return found;
    }

    static AbstractWidget find(ContainerEventHandler container, String name, boolean prefix) {
        for (var child : container.children()) {
            if (child instanceof AbstractWidget item && item.visible) {
                String label = item.getMessage().getString();
                if (prefix ? label.startsWith(name + " · ") : label.equals(name)) return item;
            }
            if (child instanceof ContainerEventHandler nested) {
                var found = find(nested, name, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void slider(String name, double fraction) { pointer(widget(name, true), fraction); }
    private boolean value(String name, int percent) {
        return widget(name, true).getMessage().getString().contains(" · " + percent + "%");
    }

    private AbstractSelectionList<?> roster() {
        return list("OnlineVoiceList");
    }

    private AbstractSelectionList<?> list(String type) {
        return mc.screen.children().stream().filter(item -> item.getClass().getSimpleName().equals(type))
                .map(item -> (AbstractSelectionList<?>) item).findFirst()
                .orElseThrow(() -> SmokeControl.failure("missing native list " + type));
    }

    private void wheel(AbstractSelectionList<?> list, double dy) {
        double x = list.getX() + list.getWidth() / 2.0;
        double y = list.getY() + list.getHeight() / 2.0;
        check(mc.screen.mouseScrolled(x, y, 0, dy), "player list did not handle wheel input");
    }

    private boolean visibleRow(AbstractSelectionList<?> list, String name) {
        double x = list.getX() + list.getWidth() / 2.0;
        for (int y = list.getY() + 1; y < list.getY() + list.getHeight() - 1; y += 2) {
            if (list.getChildAt(x, y).orElse(null) instanceof ContainerEventHandler row && find(row, name, true) != null) {
                return true;
            }
        }
        return false;
    }

    private void pointer(AbstractWidget widget, double fraction) {
        Screen screen = mc.screen;
        double x = widget.getX() + 4 + (widget.getWidth() - 8) * fraction;
        double y = widget.getY() + widget.getHeight() / 2.0;
        check(screen.mouseClicked(x, y, 0), "control ignored click: " + widget.getMessage().getString());
        screen.mouseReleased(x, y, 0);
    }

    private void keyboardSelect(AbstractWidget choice) {
        Screen screen = mc.screen;
        for (int i = 0; i < 100 && screen.getFocused() != choice; i++) {
            screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        }
        check(screen.getFocused() == choice, "theme is unreachable by keyboard");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
    }

    private void compareThemes() {
        try {
            BufferedImage first = ImageIO.read(screenshots.resolve("menu-uwu.png").toFile());
            for (var theme : BACKDROPS) {
                if (theme == MenuTheme.UWU || theme.hasScene()) continue;
                BufferedImage alternate = ImageIO.read(screenshots.resolve("menu-" + theme.id() + ".png").toFile());
                for (int band = 0; band < 3; band++) verifyPalette(first, alternate, band, theme.id());
            }
        } catch (IOException e) {
            throw SmokeControl.failure("cannot compare theme captures");
        }
    }

    private static void verifyPalette(BufferedImage a, BufferedImage b, int band, String theme) {
        int changed = 0;
        int total = 0;
        for (int y = 55 + band * 110; y < 155 + band * 110; y += 5) {
            for (int x = 180; x < 675; x += 5) {
                int left = a.getRGB(x, y);
                int right = b.getRGB(x, y);
                int distance = 0;
                for (int shift = 0; shift <= 16; shift += 8) {
                    distance += Math.abs(((left >> shift) & 255) - ((right >> shift) & 255));
                }
                if (distance > 50) changed++;
                total++;
            }
        }
        check(changed * 4 >= total, theme + " palette band " + band + " changed " + changed + "/" + total + " samples");
    }

    private static void check(boolean success, String message) {
        if (!success) throw SmokeControl.failure(message);
    }
}

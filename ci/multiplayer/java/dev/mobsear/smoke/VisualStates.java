package dev.mobsear.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.client.VoiceSettingsScreen;
import dev.mobsear.net.MicState;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Only fixture controls use the shared temporary directory. All voice and cosmetic state cross game connections. */
final class VisualStates {
    private static final List<String> FAMILIES = List.of("uwu", "emo", "vanilla", "amethyst");
    private static final UUID SPEAKER = UUID.nameUUIDFromBytes(("OfflinePlayer:" + SmokeControl.SPEAKER).getBytes(StandardCharsets.UTF_8));
    private static final UUID LISTENER = UUID.nameUUIDFromBytes(("OfflinePlayer:" + SmokeControl.LISTENER).getBytes(StandardCharsets.UTF_8));
    private static final Path SYNC = Path.of(System.getenv("MOBSEAR_SMOKE_DIR"));
    private record Case(String style, MicState state, String effects, String name, boolean hidden, boolean menu) {}
    private static final List<Case> CASES = cases();
    private static List<Case> cases() {
        var all = new ArrayList<Case>();
        for (String style : FAMILIES) {
            for (var state : List.of(MicState.IDLE, MicState.MUTED, MicState.DEAFENED, MicState.SPEAKING)) {
                all.add(new Case(style, state, "full", style + "-" + state.name().toLowerCase(java.util.Locale.ROOT), false, false));
            }
            if (style.equals("uwu")) {
                all.add(new Case(style, MicState.IDLE, "full", "uwu-stopped", false, false));
                all.add(new Case(style, MicState.MUTED, "full", "uwu-muted-after-speaking", false, false));
            }
        }
        for (String effects : List.of("reduced", "off"))
            all.add(new Case("uwu", MicState.SPEAKING, effects, "effects-" + effects, false, false));
        all.add(new Case("uwu", MicState.SPEAKING, "full", "hidden", true, false));
        all.add(new Case("uwu", MicState.SPEAKING, "full", "status-speaking", false, true));
        all.add(new Case("uwu", MicState.IDLE, "full", "status-quiet", false, true));
        all.add(new Case("uwu", MicState.IDLE, "full", "gain-zero", false, true));
        all.add(new Case("uwu", MicState.IDLE, "full", "device-meter", false, true));
        return List.copyOf(all);
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final boolean sender;
    private final Path output = SYNC.resolve("visuals");
    private final BufferedImage[] atlases = new BufferedImage[FAMILIES.size()];
    private int index, rendered, firstFrame = -1, observedTag = -2;
    private boolean configured, captured, reported, requestedMenu, vanillaWouldRender;
    private boolean wasMuted, wasVoiceActivated;
    private int namePhase;
    private final java.util.BitSet teamLetters = new java.util.BitSet();
    private long deadline, firstAt, tagAt;

    VisualStates(boolean sender) {
        this.sender = sender;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::tag);
        NeoForge.EVENT_BUS.addListener(this::render);
        try {
            Files.createDirectories(output);
            for (int i = 0; i < atlases.length; i++) {
                String path = "textures/font/mic_" + FAMILIES.get(i) + ".png";
                try (var input = mc.getResourceManager().open(ResourceLocation.fromNamespaceAndPath("mobsear", path))) {
                    atlases[i] = ImageIO.read(input);
                }
            }
        } catch (IOException ex) { throw SmokeControl.failure("cannot load fixture imagery: " + ex); }
        VoiceClient.options().setTheme("uwu");
        VoiceClient.options().setNoiseSuppression(true);
        GLFW.glfwFocusWindow(mc.getWindow().getWindow());
        deadline = System.nanoTime() + 30_000_000_000L;
    }

    private void tag(RenderNameTagEvent event) {
        if (sender || !event.getEntity().getUUID().equals(SPEAKER)) return;
        String label = event.getContent().getString();
        observedTag = label.chars().filter(c -> c >= 0xe100 && c < 0xe100 + 4*6*13).findFirst().orElse(-1);
        if (namePhase <= SmokeControl.TEAM_VISIBLE) {
            try {
                var renderRule = net.minecraft.client.renderer.entity.EntityRenderer.class.getDeclaredMethod(
                        "shouldShowName", net.minecraft.world.entity.Entity.class);
                renderRule.setAccessible(true);
                vanillaWouldRender = (boolean) renderRule.invoke(event.getEntityRenderer(), event.getEntity());
            } catch (ReflectiveOperationException ex) { throw SmokeControl.failure("cannot verify vanilla label rule: " + ex); }
        }
        tagAt = System.nanoTime();
    }

    void tick() {
        if (reported) return;
        if (System.nanoTime() > deadline) throw SmokeControl.failure("visual step " + index + " sender=" + sender
                + " namePhase=" + namePhase + " local=" + VoiceClient.localState() + " remote=" + VoiceClient.remoteState(SPEAKER)
                + " tag=" + observedTag + " fresh=" + recentTag() + " peer=" + mc.level.getPlayerByUUID(SPEAKER)
                + " focus=" + mc.isWindowActive() + " menu=" + mc.screen + " frames=" + rendered);
        if (mc.screen instanceof PauseScreen pause) pause.onClose();
        if (namePhase != 0) return;
        if (index == CASES.size()) {
            if (mc.screen instanceof VoiceSettingsScreen) mc.screen.onClose();
            if (!sender) checkObserverPixels();
            FixtureSound.tone = false;
            VoiceClient.setVoiceActivated(false);
            report(SmokeControl.VISUAL);
            return;
        }
        Case c = CASES.get(index);
        if (!configured) {
            var prefs = VoiceClient.options();
            prefs.setHudVisible(!sender || !c.hidden);
            prefs.setNameplates(sender || !c.hidden);
            prefs.setEffects(c.effects);
            if (c.name.equals("uwu-speaking")) {
                FixtureSound.checkPeerTone(sender?800:400);
                if (!sender) {
                    wasMuted=prefs.muted();
                    wasVoiceActivated=VoiceClient.voiceActivated();
                    prefs.setMuted(false);
                    VoiceClient.setVoiceActivated(true);
                    FixtureSound.tone=true;
                }
            }
            if (sender) {
                prefs.setMicStyle(c.style);
                prefs.setMuted(c.state == MicState.MUTED);
                prefs.setDeafened(c.state == MicState.DEAFENED);
                FixtureSound.tone = c.state == MicState.SPEAKING || c.name.equals("uwu-muted-after-speaking");
                if (!VoiceClient.voiceActivated() || !prefs.localMobCalls() ||
                        java.util.Arrays.stream(mc.options.keyMappings).anyMatch(k -> k.getName().equals("key.mobsear.talk") && k.isDown())) {
                    throw SmokeControl.failure("Speaker should still be hands-free without the talk key");
                }
            } else if (c.menu && mc.screen == null && !requestedMenu) {
                MenuSmoke.pressG();
                requestedMenu = true;
                return;
            }
            if (!sender && c.menu && !(mc.screen instanceof VoiceSettingsScreen)) return;
            if (!sender && c.name.equals("gain-zero")) {
                var gain = MenuSmoke.find(mc.screen, SmokeControl.SPEAKER, true);
                if (gain == null || gain.getWidth() <= 12) return;
                double x = gain.getX() + 4, y = gain.getY() + gain.getHeight() / 2.0;
                if (!mc.screen.mouseClicked(x, y, 0)) return;
                mc.screen.mouseReleased(x, y, 0);
            }
            if (!sender && c.name.equals("device-meter")) {
                prefs.setMuted(false);
                FixtureSound.tone = true;
                VoiceClient.setTestingMic(true);
            }
            configured = true;
        }
        if (captured && Files.isRegularFile(marker(!sender))) {
            if (c.name.equals("uwu-speaking")) {
                System.out.println("MOBSEAR_TWO_WAY_PLAYBACK player="+mc.getUser().getName()+" peerHz="+(sender?800:400)+
                        " matchingOutputFrames="+FixtureSound.peerToneFrames.get()+" bothSpeaking=true");
                FixtureSound.checkPeerTone(0);
                if (!sender) {
                    FixtureSound.tone=false;
                    VoiceClient.options().setMuted(wasMuted);
                    VoiceClient.setVoiceActivated(wasVoiceActivated);
                }
            }
            System.out.println("MOBSEAR_VISUAL_STEP " + c.name + " passed");
            index++;
            configured = captured = requestedMenu = false;
            rendered = 0;
            firstFrame = -1;
            deadline = System.nanoTime() + 30_000_000_000L;
        }
    }

    void inspectName(int phase) {
        namePhase = phase;
        reported = false;
        rendered = 0;
        tagAt = 0;
        deadline = System.nanoTime() + 30_000_000_000L;
    }

    private void render(RenderFrameEvent.Post ignored) {
        if (reported || !VoiceClient.connected() || mc.player == null) return;
        if (namePhase != 0) {
            inspectRenderedName();
            return;
        }
        if (index >= CASES.size() || !configured || captured) return;
        Case c = CASES.get(index);
        MicState state = sender ? VoiceClient.localState() : VoiceClient.remoteState(SPEAKER);
        String style = sender ? VoiceClient.localStyle() : VoiceClient.remoteStyle(SPEAKER);
        if (state != c.state || !style.equals(c.style)) return;
        if (c.state == MicState.SPEAKING && (sender ? VoiceClient.inputLevel() : VoiceClient.remoteLevel(SPEAKER)) <= 0) return;
        if (c.name.equals("uwu-speaking") && (VoiceClient.localState()!=MicState.SPEAKING ||
                VoiceClient.remoteState(sender?LISTENER:SPEAKER)!=MicState.SPEAKING || FixtureSound.peerToneFrames.get()<4)) return;
        if (c.menu) {
            if (sender) { finish(); return; }
            String label = FixtureSound.rowStatus();
            if (!label.equals(c.state == MicState.SPEAKING ? "Speaking" : "Quiet")) return;
            if (c.state == MicState.SPEAKING && FixtureSound.played.get() == 0) return;
            if (c.name.equals("gain-zero") && VoiceClient.options().gain(SPEAKER) != 0) return;
            if (c.name.equals("device-meter") && (!VoiceClient.testingMic() || VoiceClient.inputLevel() <= 0
                    || VoiceClient.localState() != MicState.IDLE)) return;
            if (++rendered < 4) return;
            try (var picture = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { write(picture, c.name); }
            finish();
            return;
        }
        if (mc.screen != null || (!sender && !recentTag()) || ++rendered < 3) return;
        int family = FAMILIES.indexOf(c.style);
        try (var picture = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int frame = sender ? findHud(picture, family) : observedTag < 0 ? -1 : observedTag - 0xe100 - family*6*13;
            if (!expected(c, frame)) return;
            String name = (sender ? "hud-" : "nameplate-") + c.name;
            if (firstFrame == -1 && !c.hidden) {
                firstFrame = frame;
                firstAt = System.nanoTime();
                write(picture, name);
            }
            if (c.hidden) write(picture, name);
            else if (c.state == MicState.SPEAKING && c.effects.equals("full")) {
                if (frame == firstFrame) return;
                write(picture, name + "-next");
                if (!sender && c.name.equals("uwu-speaking"))
                    System.out.println("MOBSEAR_DEFAULT_VOICE_WIRE_PASS observer decoded and played hands-free speaker audio");
            } else if (c.state == MicState.SPEAKING) {
                if (frame != firstFrame) throw SmokeControl.failure("reduced/off glyph moved at steady input");
                if (System.nanoTime() - firstAt < 450_000_000L) return;
            }
            finish();
        }
    }

    private void inspectRenderedName() {
        if (sender) { report(namePhase); return; }
        var peer = mc.level.getPlayerByUUID(SPEAKER);
        if (peer == null || !recentTag()) return;
        double distance = peer.distanceToSqr(mc.player);
        boolean far = namePhase == SmokeControl.FAR;
        boolean hidden = namePhase == SmokeControl.TEAM_HIDDEN;
        if (far) {
            if (distance < 33*33 || VoiceClient.remoteState(SPEAKER) != null || observedTag != -1) return;
        } else if (distance > 5*5 || peer.getTeam() == null || (VoiceClient.remoteState(SPEAKER) == null) != hidden ||
                (observedTag == -1) != hidden ||
                (peer.getTeam().getNameTagVisibility() == net.minecraft.world.scores.Team.Visibility.NEVER) != hidden ||
                vanillaWouldRender == hidden) return;
        if (++rendered < 6) return;
        try (var picture = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            if (!far) compareTeamLetters(picture, hidden);
            write(picture, far ? "nameplate-out-of-range" : hidden ? "nameplate-team-hidden" : "nameplate-team-visible");
        }
        report(namePhase);
    }

    /** Vanilla white name pixels above the peer's head disappear on the same camera after team visibility changes. */
    private void compareTeamLetters(NativeImage picture, boolean hidden) {
        var mask = new java.util.BitSet();
        for (int y = 95; y < 225; y++) for (int x = 310; x < 544; x++) {
            int pixel = picture.getPixelRGBA(x, y);
            if ((pixel & 255) > 243 && (pixel >> 8 & 255) > 243 && (pixel >> 16 & 255) > 243) {
                mask.set(y*picture.getWidth()+x);
            }
        }
        if (!hidden) {
            if (mask.cardinality() < 24) throw SmokeControl.failure("control capture has no legible in-world name");
            teamLetters.or(mask);
        } else {
            var gone = (java.util.BitSet) teamLetters.clone();
            gone.andNot(mask);
            if (gone.cardinality() < 24 || gone.cardinality() * 2 < teamLetters.cardinality()) {
                throw SmokeControl.failure("team-hidden capture retained the white name text");
            }
        }
    }

    private boolean recentTag() { return System.nanoTime() - tagAt < 500_000_000L; }

    // Inspect the observer's saved framebuffer, independently of the packet, render event and glyph ID.
    // The fixture holds both players and the camera still; the right edge is measured from its idle control.
    private java.util.BitSet pinkPixels(String state) throws IOException {
        var image = ImageIO.read(output.resolve("nameplate-" + state + ".png").toFile());
        int left = image.getWidth()/2 + 37, top = image.getHeight()/2 - 57;
        var mask = new java.util.BitSet(56 * 42);
        for (int dy = 0; dy < 42; dy++) for (int dx = 0; dx < 56; dx++) {
            int color = image.getRGB(left + dx, top + dy);
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            if (r > 135 && r - g > 25 && b - g > 9 && r > b) mask.set(dy * 56 + dx);
        }
        return mask;
    }

    private void checkObserverPixels() {
        try {
            var idle = pinkPixels("uwu-idle");
            var stopped = pinkPixels("uwu-stopped");
            var muted = pinkPixels("uwu-muted-after-speaking");
            if (idle.cardinality() < 40 || !idle.equals(stopped) || muted.cardinality() < 20 ||
                    muted.equals(stopped) || !muted.equals(pinkPixels("uwu-muted")))
                throw SmokeControl.failure("The observer's idle and muted mic images did not return to their reference shapes");
            int right = idle.stream().map(bit -> bit % 56).max().orElseThrow();
            var space = new java.util.BitSet(56 * 42);
            for (int row = 0; row < 42; row++) space.set(row * 56 + right + 3, (row + 1) * 56);
            var current = pinkPixels("uwu-speaking");
            var next = pinkPixels("uwu-speaking-next");
            current.and(space);
            next.and(space);
            if (current.cardinality() < 2 || next.cardinality() < 2 || current.equals(next))
                throw SmokeControl.failure("Detached particles did not move across two observer frames");
            for (String quiet : List.of("uwu-stopped", "uwu-muted-after-speaking", "effects-reduced", "effects-off")) {
                var particles = pinkPixels(quiet);
                particles.and(space);
                if (!particles.isEmpty()) throw SmokeControl.failure("Detached particles remained on the observer: " + quiet);
            }
            System.out.println("MOBSEAR_OBSERVER_PIXELS_PASS idle=" + idle.cardinality() + " muted=" + muted.cardinality()
                    + " movingParticlePixels=" + current.cardinality() + "," + next.cardinality()
                    + " stopped=0 mutedParticles=0 reduced=0 effectsOff=0");
        } catch (IOException ex) { throw SmokeControl.failure("Cannot inspect observer framebuffer captures: " + ex); }
    }
    private static boolean expected(Case c, int frame) {
        if (c.hidden) return frame == -1;
        return switch (c.state) {
            case IDLE -> frame == 0;
            case MUTED -> frame == 1;
            case DEAFENED -> frame == 2;
            case SPEAKING -> c.effects.equals("full") ? frame >= 5 && frame <= 12 : frame == 3 || frame == 4;
        };
    }

    /** Identify the rendered HUD from its opaque texture pixels, including the actual motion frame. */
    private int findHud(NativeImage screen, int family) {
        int scale = (int) mc.getWindow().getGuiScale();
        int top = (mc.getWindow().getGuiScaledHeight() - 29) * scale;
        var atlas = atlases[family];
        int best = -1;
        double highest = .9;
        for (int frame = 0; frame < 13; frame++) {
            int count = 0, matches = 0;
            for (int y = 0; y < 22; y++) for (int x = 0; x < 22; x++) {
                int source = atlas.getRGB(frame*22+x, y);
                if (source >>> 24 != 255) continue;
                int color = screen.getPixelRGBA((7+x)*scale+scale/2, top+y*scale+scale/2);
                int rgb = (color & 255) << 16 | color & 0xff00 | color >> 16 & 255;
                count++;
                if ((source & 0xffffff) == rgb) matches++;
            }
            double ratio = (double) matches / count;
            if (ratio > highest) { best = frame; highest = ratio; }
        }
        return best;
    }

    private Path marker(boolean fromSender) { return SYNC.resolve("visual-" + index + (fromSender ? "-tx" : "-rx")); }
    private void finish() {
        captured = true;
        try { Files.createFile(marker(sender)); }
        catch (IOException ex) { throw SmokeControl.failure("could not advance fixture: " + ex); }
    }
    private void write(NativeImage picture, String name) {
        try { picture.writeToFile(output.resolve(name + ".png")); }
        catch (IOException ex) { throw SmokeControl.failure("could not save " + name + ": " + ex); }
    }
    private void report(int phase) {
        reported = true;
        PacketDistributor.sendToServer(new SmokeControl.Barrier(phase));
    }
}

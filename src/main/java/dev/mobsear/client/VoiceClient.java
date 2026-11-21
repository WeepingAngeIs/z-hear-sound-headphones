package dev.mobsear.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.MobsEar;
import dev.mobsear.net.Cue;
import dev.mobsear.net.MicPresence;
import dev.mobsear.net.MicState;
import dev.mobsear.net.Packets;
import dev.mobsear.net.VoiceSignal;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod(value = "mobsear", dist = Dist.CLIENT)
public final class VoiceClient {
    private static VoiceClient instance;
    private record Seen(MicPresence.Nearby snapshot, long arrival) {}
    private final HashMap<UUID, Seen> nearby = new HashMap<>();
    private final KeyMapping talk = mapping("talk", GLFW.GLFW_KEY_V);
    private final KeyMapping toggle = mapping("recognition", GLFW.GLFW_KEY_N);
    private final KeyMapping settings = mapping("settings", GLFW.GLFW_KEY_G);
    private final KeyMapping mute = mapping("mute", GLFW.GLFW_KEY_M);
    private final KeyMapping deafen = mapping("deafen", GLFW.GLFW_KEY_UNKNOWN);
    private final VoicePreferences preferences = new VoicePreferences(FMLPaths.CONFIGDIR.get().resolve("mobsear-client.properties"));
    private ClientPacketListener connection;
    private Session session;
    private boolean recognition;
    private boolean activation;
    private boolean testMic;
    private boolean horde;
    private MicPresence.Controls advertised;
    private int sinceAdvertised;
    private boolean waitForRelease;

    public VoiceClient(IEventBus bus, ModContainer container) {
        instance = this;
        bus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(talk);
            event.register(toggle);
            event.register(settings);
            event.register(mute);
            event.register(deafen);
        });
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> settingsScreen(parent));
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> disconnect());
        MobsEar.playback = this::receive;
        MobsEar.presence = packet -> nearby.put(packet.speaker(), new Seen(packet, System.nanoTime()));
        MobsEar.hordeStatus = enabled -> horde = enabled;
    }

    public static boolean connected() { return instance != null && instance.session != null; }
    public static VoicePreferences options() { return instance.preferences; }
    public static boolean voiceActivated() { return connected() && instance.activation; }
    public static void setVoiceActivated(boolean enabled) {
        if (connected()) instance.activation = enabled;
    }
    public static boolean testingMic() { return connected() && instance.testMic; }
    public static void setTestingMic(boolean enabled) {
        if (connected()) instance.testMic = enabled && Minecraft.getInstance().screen instanceof VoiceSettingsScreen;
    }
    public static boolean hordeEnabled() { return connected() && instance.horde; }
    public static void setHordeEnabled(boolean enable) {
        var link = Minecraft.getInstance().getConnection();
        if (connected() && link != null && link.hasChannel(Packets.HordeOpt.TYPE) && (!enable || MobsEar.hordeAvailable())) {
            PacketDistributor.sendToServer(new Packets.HordeOpt(enable));
        }
    }
    public static String localStyle() { return options().resolvedMicStyle(); }
    public static double inputLevel() {
        return connected() && !options().muted() && !options().deafened() ? instance.session.meterLevel() : 0;
    }
    public static MicState localState() {
        if (!connected()) return MicState.IDLE;
        if (options().deafened()) return MicState.DEAFENED;
        if (options().muted()) return MicState.MUTED;
        return instance.session.speaking() ? MicState.SPEAKING : MicState.IDLE;
    }
    public static MicState remoteState(UUID player) {
        var status = remote(player);
        return status == null ? null : status.state();
    }
    public static double remoteLevel(UUID player) {
        var status = remote(player);
        return status == null ? 0 : status.level() / 255.0;
    }
    public static String remoteStyle(UUID player) {
        var status = remote(player);
        return status == null ? "uwu" : MicPresence.styleName(status.style());
    }
    private static MicPresence.Nearby remote(UUID id) {
        if (!connected()) return null;
        var seen = instance.nearby.get(id);
        var mc = Minecraft.getInstance();
        if (seen == null || mc.level == null || mc.player == null || mc.getPlayerSocialManager().shouldHideMessageFrom(id)) return null;
        var pkt = seen.snapshot();
        if (!pkt.dimension().equals(mc.level.dimension().location()) || System.nanoTime() - seen.arrival() > 1_000_000_000L) return null;
        var other = mc.level.getPlayerByUUID(id);
        if (other == null || other == mc.player || !other.isAlive() || other.isSpectator() || !MicPresence.nameVisible(other, mc.player)
                || other.distanceToSqr(mc.player) >= (double) pkt.radius() * pkt.radius()) return null;
        return pkt;
    }

    private static KeyMapping mapping(String action, int key) {
        return new KeyMapping("key.mobsear." + action, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                key, "key.categories.mobsear");
    }

    private Screen settingsScreen(Screen parent) {
        var mc = Minecraft.getInstance();
        preferences.bindPitchAccount(mc.getUser().getProfileId());
        var link = mc.getConnection();
        boolean available = mc.player != null && link != null && link.hasChannel(Packets.Call.TYPE);
        return new VoiceSettingsScreen(parent, preferences, () -> recognition,
                available ? enabled -> recognition = enabled : null,
                uuid -> session != null && session.activity.speaking(uuid, System.nanoTime()));
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        preferences.bindPitchAccount(mc.getUser().getProfileId());
        var current = mc.getConnection();
        if (connection != current) {
            disconnect();
            connection = current;
            waitForRelease = talk.isDown();
        }
        while (settings.consumeClick()) {
            if (mc.screen == null && mc.player != null) mc.setScreen(settingsScreen(null));
        }
        boolean focused = mc.screen == null && mc.isWindowActive() && !mc.isPaused();
        boolean usable = current != null && mc.level != null && mc.player != null && mc.player.isAlive()
                && !mc.player.isSpectator() && current.hasChannel(Packets.Up.TYPE);
        while (toggle.consumeClick()) {
            if (usable && focused) recognition = !recognition;
        }
        while (mute.consumeClick()) if (usable && focused) {
            preferences.setMuted(!preferences.muted());
            preferences.save();
        }
        while (deafen.consumeClick()) if (usable && focused) {
            preferences.setDeafened(!preferences.deafened());
            preferences.save();
        }
        if (session != null && (!usable || session.level != mc.level
                || session.deviceRevision != preferences.deviceRevision()
                || session.deafened != preferences.deafened())) closeSession();
        if (!usable) return;
        if (session == null) session = new Session(mc.level, preferences);
        publishControls(current);
        if (!(mc.screen instanceof VoiceSettingsScreen) || !mc.isWindowActive()) testMic = false;
        boolean inputBlocked = preferences.muted() || preferences.deafened();
        if (inputBlocked) testMic = false;
        if (!talk.isDown()) waitForRelease = false;
        boolean held = focused && !inputBlocked && (activation || (talk.isDown() && !waitForRelease));
        if (!focused || inputBlocked) {
            if (talk.isDown()) waitForRelease = true;
            session.suspendInput();
            session.monitor(testMic);
        }
        if (session.recognition != recognition) session.enableRecognition(recognition);
        if (focused && !inputBlocked) {
            session.setHeld(held, activation);
            session.send(current);
        }
        session.reportStatus(mc);
    }

    private void publishControls(ClientPacketListener link) {
        var controls = new MicPresence.Controls(MicPresence.styleId(preferences.resolvedMicStyle()),
                preferences.muted(), preferences.deafened());
        if (++sinceAdvertised < 16 && controls.equals(advertised)) return;
        sinceAdvertised = 0;
        advertised = controls;
        if (link.hasChannel(MicPresence.Controls.TYPE)) PacketDistributor.sendToServer(controls);
        long now = System.nanoTime();
        nearby.values().removeIf(seen -> now - seen.arrival() > 1_000_000_000L);
    }

    private void receive(Packets.Down frame) {
        var mc = Minecraft.getInstance();
        if (session == null || preferences.deafened() || mc.level != session.level || mc.player == null || !mc.player.isAlive()
                || mc.player.isSpectator() || !frame.dimension().equals(mc.level.dimension().location())
                || mc.getPlayerSocialManager().shouldHideMessageFrom(frame.speaker())) return;
        double gain = frame.gain() * (preferences.volume() / 100.0) * (preferences.gain(frame.speaker()) / 100.0);
        session.activity.heard(frame.speaker(), frame.pcm(), gain, System.nanoTime());
        session.audio.play(frame.speaker(), frame.pcm(), gain);
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
            waitForRelease = talk.isDown();
        }
        nearby.clear();
        advertised = null;
    }

    private void disconnect() {
        closeSession();
        connection = null;
        recognition = false;
        activation = false;
        testMic = false;
        horde = false;
    }

    private static final class Session {
        final ClientLevel level;
        final VoicePreferences prefs;
        final boolean deafened;
        final long deviceRevision;
        final SpeakingActivity activity = new SpeakingActivity();
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(12);
        final CaptureGate gate = new CaptureGate();
        final OutgoingPitch pitch = new OutgoingPitch();
        final AtomicReference<Cue> cue = new AtomicReference<>();
        final AtomicReference<VoiceAudio.Problem> problem = new AtomicReference<>();
        final SpeechCues speech = new SpeechCues(cue::set, FMLPaths.CONFIGDIR.get().resolve("mobsear-aliases.properties"));
        final VoiceAudio audio;
        volatile boolean held;
        boolean automatic;
        boolean monitoring;
        long lastSpeaking;
        long lastMeter;
        boolean recognition;
        boolean suspended;
        SpeechCues.Status previous = SpeechCues.Status.OFF;

        Session(ClientLevel level, VoicePreferences preferences) {
            this.level = level;
            prefs = preferences;
            deafened = preferences.deafened();
            deviceRevision = preferences.deviceRevision();
            audio = new VoiceAudio(this::capture, problem::set, preferences::microphone, preferences::speaker);
        }

        // Only the frames that enter the transmission queue are eligible for recognition.
        private synchronized void capture(byte[] frame) {
            if ((!held && !monitoring) || prefs.muted() || prefs.deafened()) return;
            lastMeter = System.nanoTime();
            gate.accept(frame, automatic && !monitoring, prefs.sensitivity(), prefs.noiseSuppression(), pcm -> {
                if (monitoring || !held) return;
                int cents = prefs.pitchEnabled() ? prefs.pitchCents() : 0;
                if (pitch.route(pcm, cents, frames::offer, speech::acceptFrame)
                        && VoiceSignal.meter(VoiceSignal.rms(pcm)) > 0) lastSpeaking = lastMeter;
            }, () -> {
                pitch.reset();
                speech.endUtterance();
            });
        }

        synchronized boolean speaking() {
            return held && lastSpeaking != 0 && System.nanoTime() - lastSpeaking < 360_000_000L;
        }

        synchronized double meterLevel() {
            return (held || monitoring) && System.nanoTime() - lastMeter < 220_000_000L ? gate.level() : 0;
        }

        synchronized void monitor(boolean enable) {
            if (monitoring == enable) return;
            monitoring = enable;
            gate.reset();
            pitch.reset();
            audio.setTransmitting(enable);
        }

        synchronized void setHeld(boolean value, boolean voiceActivation) {
            if (suspended) {
                monitor(false);
                suspended = false;
                if (recognition) speech.setEnabled(true);
            }
            if (held == value && automatic == voiceActivation) return;
            automatic = voiceActivation;
            held = value;
            gate.reset();
            pitch.reset();
            lastSpeaking = 0;
            audio.setTransmitting(value);
            if (!value) speech.endUtterance();
        }

        synchronized void suspendInput() {
            if (suspended) return;
            held = false;
            suspended = true;
            gate.reset();
            pitch.reset();
            lastSpeaking = 0;
            audio.setTransmitting(false);
            speech.setEnabled(false);
            cue.set(null);
            frames.clear();
        }

        void enableRecognition(boolean enable) {
            recognition = enable;
            speech.setEnabled(enable && !suspended);
            if (!enable) cue.set(null);
        }

        void send(ClientPacketListener link) {
            byte[] frame;
            for (int i = 0; i < 12 && (frame = frames.poll()) != null; i++) {
                PacketDistributor.sendToServer(new Packets.Up(frame));
            }
            Cue ready = cue.getAndSet(null);
            if (ready == null || !recognition || !link.hasChannel(Packets.Call.TYPE)) return;
            boolean supported = switch (ready) {
                case ZOMBIE, MOBS, ENDERMAN -> true;
                default -> link.hasChannel(MicPresence.Controls.TYPE);
            };
            if (supported) PacketDistributor.sendToServer(new Packets.Call(ready));
        }

        void reportStatus(Minecraft mc) {
            var failure = problem.getAndSet(null);
            var state = speech.status();
            if (failure != null) {
                String key = failure == VoiceAudio.Problem.MICROPHONE_UNAVAILABLE ? "mic" : "output";
                mc.player.displayClientMessage(Component.translatable("mobsear.problem." + key), true);
            } else if (state != previous && !suspended) {
                previous = state;
                String key = state.name().toLowerCase(java.util.Locale.ROOT);
                mc.player.displayClientMessage(Component.translatable("mobsear.speech." + key), true);
            }
        }

        void close() {
            suspendInput();
            audio.close();
            speech.close();
        }
    }
}

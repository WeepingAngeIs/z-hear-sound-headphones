package dev.mobsear.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.MobsEar;
import dev.mobsear.net.Cue;
import dev.mobsear.net.Packets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod(value = "mobsear", dist = Dist.CLIENT)
public final class VoiceClient {
    private final KeyMapping talk = mapping("talk", GLFW.GLFW_KEY_V);
    private final KeyMapping toggle = mapping("recognition", GLFW.GLFW_KEY_N);
    private ClientPacketListener connection;
    private Session session;
    private boolean recognition;
    private boolean waitForRelease;

    public VoiceClient(IEventBus bus) {
        bus.addListener((RegisterKeyMappingsEvent event) -> { event.register(talk); event.register(toggle); });
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> disconnect());
        MobsEar.playback = this::receive;
    }

    private static KeyMapping mapping(String action, int key) {
        return new KeyMapping("key.mobsear." + action, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                key, "key.categories.mobsear");
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var current = mc.getConnection();
        if (connection != current) {
            disconnect();
            connection = current;
            waitForRelease = talk.isDown();
        }
        boolean focused = mc.screen == null && mc.isWindowActive() && !mc.isPaused();
        boolean usable = current != null && mc.level != null && mc.player != null && mc.player.isAlive()
                && !mc.player.isSpectator() && current.hasChannel(Packets.Up.TYPE);
        while (toggle.consumeClick()) {
            if (usable && focused) recognition = !recognition;
        }
        if (!usable || (session != null && session.level != mc.level)) closeSession();
        if (!usable) return;
        if (session == null) session = new Session(mc.level);
        if (session.recognition != recognition) session.enableRecognition(recognition);
        if (!talk.isDown()) waitForRelease = false;
        boolean held = focused && talk.isDown() && !waitForRelease;
        if (!focused) {
            if (session.held) waitForRelease = true;
            session.suspendInput();
        } else {
            session.setHeld(held);
            session.send();
        }
        session.reportStatus(mc);
    }

    private void receive(Packets.Down frame) {
        var mc = Minecraft.getInstance();
        if (session == null || mc.level != session.level || mc.player == null || !mc.player.isAlive()
                || mc.player.isSpectator() || !frame.dimension().equals(mc.level.dimension().location())
                || mc.getPlayerSocialManager().shouldHideMessageFrom(frame.speaker())) return;
        session.audio.play(frame.speaker(), frame.pcm(), frame.gain());
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
            waitForRelease = talk.isDown();
        }
    }

    private void disconnect() {
        closeSession();
        connection = null;
        recognition = false;
    }

    private static final class Session {
        final ClientLevel level;
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(8);
        final AtomicReference<Cue> cue = new AtomicReference<>();
        final AtomicReference<VoiceAudio.Problem> problem = new AtomicReference<>();
        final SpeechCues speech = new SpeechCues(cue::set);
        final VoiceAudio audio = new VoiceAudio(this::capture, problem::set);
        volatile boolean held;
        boolean recognition;
        boolean suspended;
        SpeechCues.Status previous = SpeechCues.Status.OFF;

        Session(ClientLevel level) { this.level = level; }

        // Only the frames that enter the transmission queue are eligible for recognition.
        private synchronized void capture(byte[] frame) {
            if (held && frames.offer(frame)) speech.acceptFrame(frame);
        }

        synchronized void setHeld(boolean value) {
            if (suspended) {
                suspended = false;
                if (recognition) speech.setEnabled(true);
            }
            if (held == value) return;
            held = value;
            audio.setTransmitting(value);
            if (!value) speech.endUtterance();
        }

        synchronized void suspendInput() {
            if (suspended) return;
            held = false;
            suspended = true;
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

        void send() {
            byte[] frame;
            for (int i = 0; i < 8 && (frame = frames.poll()) != null; i++) {
                PacketDistributor.sendToServer(new Packets.Up(frame));
            }
            Cue ready = cue.getAndSet(null);
            if (ready != null && recognition) PacketDistributor.sendToServer(new Packets.Call(ready));
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

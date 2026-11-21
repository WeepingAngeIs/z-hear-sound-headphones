package dev.mobsear.smoke;

import dev.mobsear.MobsEar;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.net.Packets;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(value = "mobsear", dist = Dist.CLIENT)
public final class SmokeClient {
    private boolean ready;
    private boolean sender;
    private Packets.Down received;
    private int nextPhase;
    private MenuSmoke menu;
    private VisualStates visuals;
    private boolean installedSound;

    public SmokeClient() {
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        if (!installedSound) {
            FixtureSound.install();
            installedSound = true;
        }
        if (menu != null) menu.tick();
        if (visuals != null) visuals.tick();
        var mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (ready || mc.player == null || mc.level == null || connection == null) return;
        if (!connection.hasChannel(Packets.Up.TYPE) || !connection.hasChannel(SmokeControl.Barrier.TYPE)) {
            throw SmokeControl.failure("missing negotiated payload channel");
        }
        sender = SmokeControl.SPEAKER.equals(mc.getUser().getName());
        if (!sender && !SmokeControl.LISTENER.equals(mc.getUser().getName())) {
            throw SmokeControl.failure("unexpected offline username");
        }
        var production = MobsEar.playback;
        MobsEar.playback = frame -> {
            if (visuals != null) {
                production.accept(frame);
                return;
            }
            if (sender || received != null) throw SmokeControl.failure("unexpected or duplicate downlink");
            received = frame;
        };
        SmokeControl.client = this::barrier;
        ready = true;
        PacketDistributor.sendToServer(new SmokeControl.Barrier(SmokeControl.READY));
    }

    private void barrier(int phase) {
        if (phase == SmokeControl.VISUAL) {
            visuals = new VisualStates(sender);
            return;
        }
        if (phase <= SmokeControl.FAR) {
            visuals.inspectName(phase);
            return;
        }
        if (phase != nextPhase++ || phase >= SmokeControl.PHASES.length) {
            throw SmokeControl.failure("out-of-order phase");
        }
        byte[] samples = SmokeControl.generatedFrame(phase);
        if (sender) {
            PacketDistributor.sendToServer(new Packets.Up(samples));
        } else {
            boolean audible = phase != 1 && phase != 2;
            if (audible != (received != null)) throw SmokeControl.failure("incorrect near/far/dimension routing");
            if (audible) {
                UUID expected = UUID.nameUUIDFromBytes(("OfflinePlayer:" + SmokeControl.SPEAKER)
                        .getBytes(StandardCharsets.UTF_8));
                if (!received.speaker().equals(expected) || !received.dimension().equals(Level.OVERWORLD.location())
                        || Math.abs(received.gain() - 0.75f) > 0.001f || !Arrays.equals(received.pcm(), samples)) {
                    throw SmokeControl.failure("incorrect authenticated sender, dimension, gain or samples");
                }
            }
            received = null;
            if (phase == 3) {
                menu = new MenuSmoke(() -> PacketDistributor.sendToServer(new SmokeControl.Barrier(phase)));
                return;
            }
        }
        PacketDistributor.sendToServer(new SmokeControl.Barrier(phase));
    }
}

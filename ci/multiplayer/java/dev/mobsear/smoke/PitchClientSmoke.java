package dev.mobsear.smoke;

import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.PitchFixtureAudio;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.client.VoicePreferences;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

final class PitchClientSmoke {
    private record Setting(String id, int cents, boolean effect, boolean muted, boolean deaf, boolean audible) {}
    private static final List<Setting> SETTINGS = List.of(
            new Setting("bypass", VoicePreferences.SQUEAKY_CENTS, false,false,false,true),
            new Setting("squeaky", VoicePreferences.SQUEAKY_CENTS, true,false,false,true),
            new Setting("deep", VoicePreferences.DEEP_CENTS, true,false,false,true),
            new Setting("low-end", -VoicePreferences.PITCH_RANGE_CENTS, true,false,false,true),
            new Setting("high-end", VoicePreferences.PITCH_RANGE_CENTS, true,false,false,true),
            new Setting("center", 0, true,false,false,true),
            new Setting("room-dry", 0, false,false,false,true),
            new Setting("room-wet", 0, true,false,false,true),
            new Setting("mute", VoicePreferences.SQUEAKY_CENTS,true,true,false,false),
            new Setting("deafen", VoicePreferences.SQUEAKY_CENTS,true,false,true,false),
            new Setting("restored", VoicePreferences.SQUEAKY_CENTS,false,false,false,true));
    private static final Setting DISTANT = new Setting("distant", VoicePreferences.SQUEAKY_CENTS,true,false,false,false);
    private static final UUID SPEAKER = UUID.nameUUIDFromBytes(("OfflinePlayer:" + SmokeControl.SPEAKER).getBytes(StandardCharsets.UTF_8));
    private final boolean sender;
    private final int phase;
    private final Path folder = Path.of(System.getenv("MOBSEAR_SMOKE_DIR"),"pitch-audio");
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(95);
    private int index;
    private boolean configured, armed, finished;
    private long readyAt, armedAt;

    PitchClientSmoke(boolean sender, int phase) { this.sender=sender; this.phase=phase; }
    void tick() {
        if (finished) return;
        if (System.nanoTime() > deadline) throw SmokeControl.failure("pitch timed out, sender="+sender+" case="+index);
        if (!VoiceClient.connected()) return;
        if (index == (phase == PitchSmoke.FAR ? 1 : SETTINGS.size())) {
            PitchFixtureAudio.enabled=false;
            VoiceClient.setVoiceActivated(false);
            finished=true;
            PacketDistributor.sendToServer(new SmokeControl.Barrier(phase));
            return;
        }
        try { step(phase == PitchSmoke.FAR ? DISTANT : SETTINGS.get(index)); }
        catch (IOException ex) { throw SmokeControl.failure("pitch evidence file: "+ex.getMessage()); }
    }

    private void step(Setting sample) throws IOException {
        if (!configured) {
            var mc = Minecraft.getInstance();
            if (mc.screen != null) mc.screen.onClose();
            var prefs=VoiceClient.options();
            prefs.setMuted(sender && sample.muted);
            prefs.setDeafened(!sender && sample.deaf);
            prefs.setVolume(100);
            prefs.setGain(SPEAKER,100);
            prefs.setNoiseSuppression(false);
            if (sender) {
                prefs.setPitchCents(sample.cents);
                prefs.setPitchEnabled(sample.effect);
                prefs.setReverbAmount(sample.id.startsWith("room-") ? 100 : 0);
                PitchFixtureAudio.impulses=sample.id.startsWith("room-");
            }
            FixtureSound.tone=false;
            PitchFixtureAudio.enabled=sender;
            VoiceClient.setVoiceActivated(sender);
            Files.createDirectories(folder);
            Files.createFile(marker(sample, sender ? "tx" : "rx"));
            configured=true;
        }
        if (!Files.exists(marker(sample, sender ? "rx" : "tx"))) return;
        long now=System.nanoTime();
        if (readyAt == 0) readyAt=now;
        if (!sender && !Files.exists(marker(sample,"done"))) {
            // Allow previously routed samples and the effect crossfade to leave the actual output mixer.
            if (now-readyAt < 550_000_000L) return;
            if (!armed) { PitchFixtureAudio.arm(); armed=true; armedAt=now; }
            if (sample.audible ? PitchFixtureAudio.bytes() < 32000 : now-armedAt < 700_000_000L) return;
            byte[] pcm=PitchFixtureAudio.finish();
            if (sample.audible) verify(sample,pcm);
            else if (pcm.length != 0) throw SmokeControl.failure("playback leaked in "+sample.id);
            Files.createFile(marker(sample,"done"));
        }
        if (Files.exists(marker(sample,"done"))) {
            index++;
            readyAt=0;
            armed=configured=false;
        }
    }

    private Path marker(Setting s,String suffix) { return folder.resolve(s.id+"-"+suffix); }

    private void verify(Setting s,byte[] pcm) throws IOException {
        pcm=Arrays.copyOf(pcm,32000);  // Exactly one second from the playback line.
        if (s.id.startsWith("room-")) {
            verifyRoom(pcm,s.effect);
            save(s,pcm);
            return;
        }
        double expected=200*Math.pow(2,(s.effect?s.cents:0)/1200.0);
        int peak=0;
        double[] samples=new double[pcm.length/2];
        for (int i=0;i<samples.length;i++) {
            int v=(pcm[2*i]&255) | pcm[2*i+1]<<8;
            samples[i]=v;
            peak=Math.max(peak,Math.abs(v));
        }
        // Average windowed power across the clip; modulation sidebands cannot win via a single splice.
        double bestPower=0;
        int strongest=0, window=2048;
        double[] taper = new double[window];
        for (int i = 0; i < window; i++) taper[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (window - 1));
        for (int hz=75;hz<=460;hz++) {
            double power=0, coeff=2*Math.cos(2*Math.PI*hz/16000);
            for (int start=0; start+window<=samples.length; start+=window) {
                double p=0,q=0;
                for (int i=0;i<window;i++) {
                    double n=samples[start+i]*taper[i]+coeff*p-q;
                    q=p; p=n;
                }
                power+=p*p+q*q-coeff*p*q;
            }
            if (power>bestPower) { bestPower=power; strongest=hz; }
        }
        System.out.printf(java.util.Locale.ROOT,"MOBSEAR_PITCH_AUDIO %s measured=%dHz target=%.1fHz peak=%d samples=%d%n",
                s.id,strongest,expected,peak,samples.length);
        if (Math.abs(strongest-expected)>Math.max(5,expected*.075) || peak<500 || peak>=32767)
            throw SmokeControl.failure("unexpected fundamental or peak for "+s.id+": "+strongest+" Hz, peak="+peak);
        save(s,pcm);
    }

    private void verifyRoom(byte[] pcm,boolean wet) {
        int[] samples=new int[pcm.length/2];
        for (int i=0;i<samples.length;i++) samples[i]=Math.abs((pcm[2*i]&255) | pcm[2*i+1]<<8);
        int pulses=0, tailed=0;
        for (int i=1;i+1450<samples.length;i++) {
            if (samples[i]<2000 || samples[i-1]>=2000) continue;
            int reflected=0;
            for (int j=i+150;j<i+1450;j++) if (samples[j]>15) reflected++;
            pulses++;
            if (reflected>=8) tailed++;
            i+=1450;
        }
        System.out.println("MOBSEAR_REVERB_RECEIVED wet="+wet+" directPulses="+pulses+" withRoomTail="+tailed);
        if (pulses<3 || (wet ? tailed!=pulses : tailed!=0))
            throw SmokeControl.failure("Receiver impulse response did not match the chosen room amount");
    }

    private void save(Setting s,byte[] pcm) throws IOException {
        try (var stream=new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat(16000,16,1,true,false),pcm.length/2)) {
            AudioSystem.write(stream,AudioFileFormat.Type.WAVE,folder.resolve("received-"+s.id+".wav").toFile());
        }
    }
}

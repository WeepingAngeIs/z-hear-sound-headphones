package dev.mobsear.client;

import dev.mobsear.net.Packets;
import dev.mobsear.client.speech.ModelStartup;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client tick thread only. Entries accept a fixed event and an optional registered identifier. */
final class CallDebugLog {
    enum Event {
        OFF, STARTING, READY, UNAVAILABLE, STARTUP_TIMEOUT, DECODE_TIMEOUT, WORD_DECODER_ERROR,
        MICROPHONE_UNAVAILABLE, OUTPUT_UNAVAILABLE, NO_MATCH, LATE, DETECTED, HEARD,
        NO_ELIGIBLE_MOB, NO_RECENT_VOICE, COOLDOWN, GAME_MODE, INPUT_BLOCKED, SERVER_DISABLED,
        SERVER_CHANNEL_UNAVAILABLE, SERVER_REPLY_TIMEOUT
    }
    private static final Logger LOG = LoggerFactory.getLogger("mobsear.calls");
    private record Pending(ResourceLocation name, long at) {}
    private final BooleanSupplier preference;
    private final LinkedHashMap<Long, Pending> pending = new LinkedHashMap<>();
    private final HashMap<Event, Long> last = new HashMap<>();
    private Event state;
    private ModelStartup modelIssue;
    private boolean enabled;

    CallDebugLog(BooleanSupplier preference) { this.preference = preference; }

    void tick() {
        boolean nowEnabled = preference.getAsBoolean();
        if (enabled != nowEnabled) {
            enabled = nowEnabled;
            clearSession();
            last.clear();
        }
        if (!enabled) return;
        long now = System.nanoTime();
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().at < 6_000_000_000L) break;
            write(Event.SERVER_REPLY_TIMEOUT, entry.getValue().name, entry.getKey());
            it.remove();
        }
    }

    void clearSession() { pending.clear(); state = null; modelIssue = null; }

    void modelStartup(ModelStartup issue) {
        if (!enabled || !preference.getAsBoolean() || java.util.Objects.equals(issue, modelIssue)) return;
        modelIssue = issue;
        if (issue != null) LOG.info("MOBSEAR_MODEL_STARTUP stage={} reason={} code={}",
                issue.stage(), issue.reason(), issue.code());
    }

    void state(Event next) {
        if (!enabled || state == next) return;
        state = next;
        write(next, null, 0);
    }

    void write(Event event, ResourceLocation name, long request) {
        if (!enabled || !preference.getAsBoolean()) return;
        long now = System.nanoTime();
        Long previous = last.get(event);
        long interval = event == Event.NO_MATCH ? 3_000_000_000L : 750_000_000L;
        if (previous != null && now - previous < interval) return;
        last.put(event, now);
        LOG.info("MOBSEAR_CALL_DEBUG event={} target={} request={}",event.name().toLowerCase(Locale.ROOT),
                name == null ? "-" : name.toString(), request);
    }

    void sent(long id, Packets.MobCall call) {
        if (!enabled || !preference.getAsBoolean()) return;
        if (pending.size() >= 8) pending.pollFirstEntry();
        pending.put(id, new Pending(target(call), System.nanoTime()));
    }

    void reply(long id, Event outcome) {
        var call = pending.remove(id);
        if (call != null) write(outcome, call.name, id);
    }

    static ResourceLocation target(Packets.MobCall call) {
        return switch (call) {
            case Packets.SpeciesCall p -> p.species();
            case Packets.EntityCall p -> p.entityType();
            case Packets.Call p -> ResourceLocation.fromNamespaceAndPath(
                    p.cue() == dev.mobsear.net.Cue.MOBS ? "mobsear" : "minecraft", p.cue().entityPath);
        };
    }
}

package dev.mobsear.smoke;

import dev.mobsear.MobsEar;
import dev.mobsear.server.HordeMode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Hosted-only end-to-end check: production consent, server authority and legal paced voice. */
final class HordeSmoke {
    static final int DENIED = -6, JOIN = -7, BYSTANDER = -8, PARTICIPANT = -9, LEAVE = -10;
    private static final Set<UUID> acknowledgements = new HashSet<>();
    private static ServerPlayer a, b;
    private static Zombie zombie;
    private static ModConfigSpec.BooleanValue enabled;
    private static int phase, began;

    static void start(ServerPlayer participant, ServerPlayer bystander) {
        a = participant;
        b = bystander;
        var level = a.serverLevel();
        var server = level.getServer();
        var config = ModConfigs.getFileMap().get("mobsear-server.toml");
        var spec = (ModConfigSpec) config.getSpec();
        enabled = spec.getValues().get("hordeEnabled");
        if (!MobsEar.hordeAvailable() || enabled.getDefault() || opted(a) || opted(b)) {
            throw SmokeControl.failure("unexpected horde default, server offer or initial player consent");
        }
        // Keep the negotiated client offer enabled, then exercise the server's independent rejection.
        enabled.set(false);
        if (MobsEar.hordeAvailable()) throw SmokeControl.failure("failed to turn off server horde for rejection check");
        server.setDifficulty(Difficulty.NORMAL, true);
        level.setDayTime(18000);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        int y = 108, z = 84;
        for (int x = -2; x <= 9; x++) for (int dz = -2; dz <= 2; dz++) {
            level.setBlockAndUpdate(new BlockPos(x, y - 1, z + dz), Blocks.STONE.defaultBlockState());
        }
        a.setGameMode(GameType.SURVIVAL);
        b.setGameMode(GameType.SURVIVAL);
        a.teleportTo(level, .5, y, z + .5, 0, 0);
        b.teleportTo(level, 6.5, y, z + .5, 0, 0);
        zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) throw SmokeControl.failure("could not create disposable horde zombie");
        // Preserve canAttack, the normal target field and production horde logic. Remove vanilla's
        // independent nearest-player goal so it cannot fake the result or reacquire after opt-out.
        zombie.goalSelector.removeAllGoals(goal -> true);
        zombie.targetSelector.removeAllGoals(goal -> true);
        zombie.setPersistenceRequired();
        zombie.moveTo(3.5, y, z + .5, 0, 0);
        if (!level.addFreshEntity(zombie)) throw SmokeControl.failure("could not add disposable horde zombie");
        NeoForge.EVENT_BUS.addListener(HordeSmoke::tick);
        step(DENIED);
    }

    static void acknowledge(ServerPlayer player, int reported) {
        if (reported != phase || (player != a && player != b) || !acknowledgements.add(player.getUUID())) {
            throw SmokeControl.failure("invalid horde phase acknowledgment");
        }
    }

    private static boolean opted(ServerPlayer p) { return HordeMode.LIVE.optedIn(p); }

    private static void step(int next) {
        phase = next;
        began = a.serverLevel().getServer().getTickCount();
        acknowledgements.clear();
        PacketDistributor.sendToPlayer(a, new SmokeControl.Barrier(next));
        PacketDistributor.sendToPlayer(b, new SmokeControl.Barrier(next));
    }

    private static void tick(ServerTickEvent.Post event) {
        if (phase == 0) return;
        int elapsed = event.getServer().getTickCount() - began;
        if (elapsed > 350) throw SmokeControl.failure("horde phase timed out " + phase + " replies=" + acknowledgements.size());
        if (opted(b) || !zombie.isAlive() || zombie.getTarget() == b) throw SmokeControl.failure("horde involved bystander");
        if (acknowledgements.size() != 2) return;
        switch (phase) {
            case DENIED -> {
                if (opted(a) || zombie.getTarget() != null) throw SmokeControl.failure("disabled server admitted horde request");
                if (elapsed < 7) return; // Keep outside the production opt-in request cooldown.
                enabled.set(true);
                step(JOIN);
            }
            case JOIN -> {
                if (!opted(a) || zombie.getTarget() != null) throw SmokeControl.failure("wrong authenticated horde participant");
                step(BYSTANDER);
            }
            case BYSTANDER -> {
                if (zombie.getTarget() != null) throw SmokeControl.failure("unconsenting bystander's loud voice drew a horde");
                step(PARTICIPANT);
            }
            case PARTICIPANT -> {
                if (zombie.getTarget() != a) return; // The source keeps transmitting until this condition holds.
                step(LEAVE);
            }
            case LEAVE -> {
                if (opted(a) || zombie.getTarget() != null || zombie.isRemoved()) {
                    throw SmokeControl.failure("server failed to release ordinary zombie cleanly on opt-out");
                }
                zombie.discard();
                phase = 0;
                System.out.println("MOBSEAR_HORDE_WIRE_PASS disabled rejection, voluntary survival participant, bystander, routed voice, target and opt-out");
                PitchSmoke.start(a, b);
            }
            default -> throw SmokeControl.failure("unknown horde stage");
        }
    }
}

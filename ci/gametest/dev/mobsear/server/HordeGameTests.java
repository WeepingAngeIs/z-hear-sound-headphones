package dev.mobsear.server;

import com.mojang.authlib.GameProfile;
import dev.mobsear.MobsEar;
import dev.mobsear.net.Packets;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mobsear")
@PrefixGameTestTemplate(false)
public final class HordeGameTests {
    private static final HordeMode.Rules ON = new HordeMode.Rules(true, 900, 2);
    private static final HordeMode.Rules OFF = new HordeMode.Rules(false, 900, 2);
    private static final byte[] QUIET = new byte[Packets.FRAME_BYTES];
    private static final byte[] LOUD = new byte[Packets.FRAME_BYTES];
    static {
        for (int i = 1; i < LOUD.length; i += 2) LOUD[i] = (byte) ((i & 16) == 0 ? 12 : -12);
    }

    @GameTest(template = "hearing_floor")
    public static void quietConsentAndLoudness(GameTestHelper test) {
        var mode = new HordeMode();
        var player = player(test, "Quiet", 5.5, 5.5);
        var zombie = test.spawn(EntityType.ZOMBIE, 6, 1, 5);
        test.assertTrue(!MobsEar.hordeAvailable(), "The disposable server must start with quiet horror off");
        test.assertTrue(!mode.consent(player, true, OFF), "A player bypassed the server switch");
        sound(mode, player, LOUD, 0, 90);
        test.assertTrue(zombie.getTarget() == null, "Voice without consent drew a horde");
        for (var type : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
            player.setGameMode(type);
            test.assertTrue(!mode.consent(player, true, ON), "Ineligible mode joined a horde");
        }
        player.setGameMode(GameType.SURVIVAL);
        test.assertTrue(mode.consent(player, true, ON), "Survival player could not opt in");
        sound(mode, player, QUIET, 100, 100);
        sound(mode, player, LOUD, 200, 30);
        sound(mode, player, LOUD, 250, 30);
        test.assertTrue(zombie.getTarget() == null, "Silence or two short bursts started an encounter");
        sound(mode, player, LOUD, 300, 65);
        test.assertTrue(zombie.getTarget() == player, "Sustained accepted voice failed to draw the zombie");
        mode.tick(OFF, 4, 340, revoked -> test.assertTrue(revoked == player, "Revoked wrong participant"));
        test.assertTrue(!mode.optedIn(player) && zombie.getTarget() == null && !zombie.isRemoved(),
                "Disabling the server switch failed to release a world zombie");
        test.succeed();
    }

    @GameTest(template = "hearing_floor")
    public static void hordeTargetingCooldownAndCleanup(GameTestHelper test) {
        var mode = new HordeMode();
        var player = player(test, "Noise", 5.5, 5.5);
        var other = player(test, "Bystander", 6.5, 7.5);
        var first = test.spawn(EntityType.ZOMBIE, 6, 1, 5);
        var second = test.spawn(EntityType.HUSK, 4, 1, 5);
        var surplus = test.spawn(EntityType.ZOMBIE, 8, 1, 5);
        var outside = test.spawn(EntityType.ZOMBIE, 10, 1, 10);
        var engaged = test.spawn(EntityType.ZOMBIE, 5, 1, 6);
        var neutral = test.spawn(EntityType.ZOMBIFIED_PIGLIN, 5, 1, 4);
        var enderman = test.spawn(EntityType.ENDERMAN, 7, 1, 5);
        var cow = test.spawn(EntityType.COW, 5, 1, 7);
        engaged.setTarget(other);
        mode.consent(player, true, ON);
        sound(mode, player, LOUD, 1, 65);
        test.assertTrue(first.getTarget() == player && second.getTarget() == player, "Wrong nearest group");
        test.assertTrue(surplus.getTarget() == null && outside.getTarget() == null, "Count or radius exceeded");
        test.assertTrue(neutral.getTarget() == null && enderman.getTarget() == null && cow.getTarget() == null,
                "Quiet mode recruited a neutral or passive mob");
        test.assertTrue(engaged.getTarget() == other, "Quiet mode stole a bystander's existing attacker");
        mode.consent(player, false, ON);
        test.assertTrue(first.getTarget() == null && !first.isRemoved(), "Opt-out removed a world mob or left aggro");
        mode.consent(player, true, ON);
        sound(mode, player, LOUD, 80, 65);
        test.assertTrue(first.getTarget() == null, "Toggling consent bypassed cooldown");
        sound(mode, player, LOUD, 1000, 65);
        test.assertTrue(first.getTarget() == player, "Cooldown never cleared");
        player.setPos(player.position().add(0, 0, 20));
        mode.tick(ON, 4, 1040, p -> {});
        test.assertTrue(first.getTarget() == null && !first.isRemoved(), "World mob pursued voice outside radius");
        mode.clear();
        test.succeed();
    }

    @GameTest(template = "hearing_floor")
    public static void hordeUsesVanillaMelee(GameTestHelper test) {
        var mode = new HordeMode();
        var zombie = test.spawn(EntityType.ZOMBIE, 7, 1, 5);
        var player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), "MeleeFixture")) {
            boolean struck;
            @Override public boolean hurt(DamageSource source, float amount) {
                // Capture an actual melee hit; the normal NeoForge fake player is invulnerable.
                if (source.getDirectEntity() == zombie && amount > 0) struck = true;
                return struck;
            }
        };
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(test.absoluteVec(new Vec3(4.5, 1, 5.5)));
        mode.consent(player, true, ON);
        sound(mode, player, LOUD, 0, 65);
        test.assertTrue(zombie.getTarget() == player, "Horde did not assign melee target");
        test.succeedWhen(() -> {
            test.assertTrue(player.struck, "Vanilla zombie AI never closed the gap and struck its target");
            mode.clear();
        });
    }

    @GameTest(template = "hearing_floor")
    public static void temporaryWaveSafety(GameTestHelper test) {
        var mode = new HordeMode();
        var player = player(test, "WaveOwner", 2.5, 5.5);
        var bystander = player(test, "WaveOther", 1.5, 2.5);
        mode.consent(player, true, ON);
        BlockPos spawn = test.absolutePos(new BlockPos(9, 1, 5));
        test.assertTrue(mode.trySpawnAt(player, player.blockPosition(), 12) == null, "Wave spawned on speaker");
        test.assertTrue(mode.trySpawnAt(player, spawn, 4) == null, "Wave spawned outside voice range");
        // Seal a dark two-block-high room on the structure's stone floor. Allow lighting to settle.
        for (int x = 8; x <= 10; x++) {
            for (int z = 4; z <= 6; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (x != 9 || z != 5 || y == 3) test.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        test.succeedWhen(() -> {
            var zombie = mode.trySpawnAt(player, spawn, 12);
            test.assertTrue(zombie != null, "Valid loaded dark chamber did not admit a temporary zombie");
            test.assertTrue(HordeMode.temporary(zombie) && zombie.getTarget() == player, "Wrong wave owner");
            zombie.setTarget(bystander);
            test.assertTrue(zombie.getTarget() != bystander, "Temporary wave attacked another player");
            var reload = new EntityJoinLevelEvent(zombie, test.getLevel(), true);
            HordeMode.loaded(reload);
            test.assertTrue(reload.isCanceled(), "Saved temporary mob survived simulated world reload");
            mode.forget(player);
            test.assertTrue(zombie.isRemoved(), "Opt-out or disconnect left a temporary mob alive");
        });
    }

    private static void sound(HordeMode mode, ServerPlayer player, byte[] pcm, long start, int frames) {
        // 50 accepted 20 ms frames per second at a 20 Hz server tick rate.
        for (int i = 0; i < frames; i++) mode.heard(player, pcm, 4, ON, start + i * 2L / 5);
    }

    private static ServerPlayer player(GameTestHelper test, String name, double x, double z) {
        var player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(test.absoluteVec(new Vec3(x, 1, z)));
        return player;
    }
}

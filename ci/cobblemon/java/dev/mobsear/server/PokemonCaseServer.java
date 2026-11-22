package dev.mobsear.server;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

final class PokemonCaseServer {
    private static final int Y=104, Z=8;
    private final Set<UUID> arrivals = new HashSet<>(), acknowledgements = new HashSet<>();
    private ServerPlayer caller, viewer;
    private Mob subject;
    private EmptyPokeBallEntity ball;
    private PlayerPartyStore ownedStore;
    private Pokemon owned;
    private PokemonCase c;
    private int index, phase, age, aligned;
    private boolean live, reacted, fired, captureLockSeen;
    private double advance;

    PokemonCaseServer() { NeoForge.EVENT_BUS.addListener(this::tick); }

    void handle(ServerPlayer p, PokemonProbe.Message m) {
        if (m.stage() == PokemonProbe.HELLO) {
            if (!arrivals.add(p.getUUID())) throw PokemonProbe.fail("Duplicate connection");
            var list = p.serverLevel().getServer().getPlayerList();
            boolean solo = !p.serverLevel().getServer().isDedicatedServer();
            caller = list.getPlayerByName(solo ? "PokemonHome" : "PokemonCaller");
            viewer = solo ? caller : list.getPlayerByName("PokemonViewer");
            if (arrivals.size() == (solo ? 1 : 2)) {
                if (caller == null || viewer == null) throw PokemonProbe.fail("Missing genuine participants");
                var world = caller.serverLevel();
                for (var id : new PokemonCase[]{PokemonCase.PIKACHU, PokemonCase.ADDON, PokemonCase.NAMESPACE})
                    if (PokemonSpecies.getByIdentifier(id.species) == null) throw PokemonProbe.fail("Missing registry " + id.species);
                world.setDayTime(1000);
                world.setWeatherParameters(60000,0,false,false);
                world.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,world.getServer());
                for (int x=-5;x<=42;x++) for (int z=-7;z<=8;z++)
                    world.setBlockAndUpdate(new BlockPos(x,Y-1,Z+z),Blocks.STONE.defaultBlockState());
                caller.setGameMode(GameType.SURVIVAL);
                if (!solo) viewer.setGameMode(GameType.SPECTATOR);
                live = true;
                prepare();
            }
            return;
        }
        if (!live || (p != caller && p != viewer) || m.scenario()!=index || m.entity()!=subject.getId())
            throw PokemonProbe.fail("Invalid barrier owner or subject");
        if (m.stage() == PokemonProbe.FIRE && p == caller && phase == PokemonProbe.FIRE && !fired) {
            fired = true;
            return;
        }
        int expected = phase == PokemonProbe.BEFORE ? PokemonProbe.BEFORE : PokemonProbe.SEEN;
        if (m.stage()!=expected || !acknowledgements.add(p.getUUID())) throw PokemonProbe.fail("Unordered barrier " + m);
    }

    private void prepare() {
        c = PokemonCase.values()[index];
        var world = caller.serverLevel();
        var config=(ModConfigSpec)ModConfigs.getFileMap().get("mobsear-server.toml").getSpec();
        ModConfigSpec.DoubleValue chance=config.getValues().get("wildPokemonApproachChance");
        chance.set(c==PokemonCase.LOOK ? 0d : c==PokemonCase.APPROACH ? 1d : .20d);
        caller.teleportTo(world,c.distance+.5,Y,Z+.5,90,3);
        if (viewer!=caller) viewer.teleportTo(world,3.5,Y+0.1,Z+5.5,148,9);
        if (c == PokemonCase.PASSIVE) {
            subject=EntityType.COW.create(world);
        } else {
            var entity = PokemonProperties.Companion.parse("pikachu level=15").createEntity(world);
            entity.getPokemon().setSpecies(PokemonSpecies.getByIdentifier(c.species));
            subject=entity;
            if (c==PokemonCase.OWNER || c==PokemonCase.OFFLINE_OWNER) {
                var storage = Cobblemon.INSTANCE.getStorage();
                UUID owner = c==PokemonCase.OWNER ? caller.getUUID() : UUID.fromString("b885e8b7-29b9-422d-a75c-29f4a7470827");
                ownedStore = storage.getParty(owner, world.registryAccess());
                owned=entity.getPokemon();
                if (!ownedStore.add(owned) || !owner.equals(owned.getOwnerUUID()) || owned.isWild())
                    throw PokemonProbe.fail("Cobblemon party did not establish ownership " + c);
                if (c==PokemonCase.OFFLINE_OWNER && owned.getOwnerPlayer()!=null) throw PokemonProbe.fail("Owner unexpectedly online");
            }
        }
        if (subject==null) throw PokemonProbe.fail("Could not create actual subject");
        subject.moveTo(.5,Y,Z+.5,90,0);
        subject.setPersistenceRequired();
        if (!world.addFreshEntity(subject)) throw PokemonProbe.fail("Could not spawn actual subject");
        phase=PokemonProbe.BEFORE; age=aligned=0; reacted=fired=captureLockSeen=false; advance=0;
        acknowledgements.clear();
        announce();
    }
    private void announce() {
        var m = new PokemonProbe.Message(phase,index,subject.getId());
        PacketDistributor.sendToPlayer(caller,m);
        if (caller!=viewer) PacketDistributor.sendToPlayer(viewer,m);
    }
    private static void idle(Mob entity) {
        entity.getNavigation().stop();
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }
    private void tick(ServerTickEvent.Post event) {
        if (!live) return;
        age++;
        if (age>1100) throw PokemonProbe.fail(c + " timed out, phase=" + phase + " fired=" + fired + " acks="
                + acknowledgements.size() + " reacted=" + reacted + " aligned=" + aligned + " forward=" + advance);
        int count=caller==viewer ? 1:2;
        if (phase==PokemonProbe.BEFORE) {
            subject.moveTo(.5,Y,Z+.5,90,0);
            subject.setYHeadRot(90); subject.yBodyRot=90;
            idle(subject);
            if (age<36 || acknowledgements.size()!=count) return;
            if (c==PokemonCase.BATTLE) engageBattle();
            if (c==PokemonCase.CAPTURE) engageCapture();
            phase=PokemonProbe.FIRE;
            age=0; acknowledgements.clear(); announce();
            return;
        }
        boolean active=PokemonReactions.isReacting(subject);
        if (!active && !fired && c.controlled()) idle(subject);
        if (active && !c.accepts()) throw PokemonProbe.fail("Protected or wrong subject reacted: " + c);
        if (active) {
            reacted=true;
            double yaw=Math.toDegrees(Math.atan2(caller.getZ()-subject.getZ(),caller.getX()-subject.getX()))-90;
            if (Math.abs(Mth.wrapDegrees(subject.getYHeadRot()-yaw))<30) aligned++;
            advance=Math.max(advance,subject.getX()-.5);
            if (subject.getTarget()!=null) throw PokemonProbe.fail("Species call assigned combat target");
        }
        if (acknowledgements.size()!=count || !fired) return;
        if (c.accepts() && (!reacted || aligned<2)) return;
        if (c==PokemonCase.LOOK && advance>.6) throw PokemonProbe.fail("Controlled look moved " + advance);
        if (c==PokemonCase.APPROACH && advance<.55) return;
        if (c==PokemonCase.APPROACH && advance>4.8) throw PokemonProbe.fail("Approached beyond fixed short route");
        if (age < (c.controlled()?45:27)) return;
        System.out.printf(java.util.Locale.ROOT,"POKEMON_CASE_PASS %s species=%s radius=32 startDistance=%d reacted=%s aligned=%d forward=%.2f captureLock=%s%n",
                c,c.species,c.distance,reacted,aligned,advance,captureLockSeen);
        if (c==PokemonCase.BATTLE) {
            var battle=((PokemonEntity)subject).getBattle();
            if (battle==null) throw PokemonProbe.fail("Battle terminated before safety check");
            battle.stop();
        }
        if (ball!=null) { ball.discard(); ball=null; }
        subject.discard();
        if (ownedStore!=null) { ownedStore.remove(owned); ownedStore=null; owned=null; }
        int limit="1".equals(System.getenv("MOBSEAR_POKEMON_ACOUSTIC")) ? 2 : PokemonCase.values().length;
        if (++index<limit) prepare();
        else {
            live=false;
            System.out.println(caller==viewer ? "MOBSEAR_POKEMON_SOLO_PASS" : "MOBSEAR_POKEMON_MULTI_PASS");
        }
    }
    private void engageBattle() {
        var party=Cobblemon.INSTANCE.getStorage().getParty(caller);
        party.add(PokemonProperties.Companion.parse("bulbasaur level=20").create());
        var entity=(PokemonEntity)subject;
        var result=BattleBuilder.INSTANCE.pve(caller,entity);
        if (!entity.isBattling() || entity.getBattle()==null)
            throw PokemonProbe.fail("Native wild battle did not start: " + result.getClass().getSimpleName());
    }
    private void engageCapture() {
        var entity=(PokemonEntity)subject;
        ball=new EmptyPokeBallEntity(caller.serverLevel());
        ball.setOwner(caller); ball.moveTo(entity.getX(),entity.getY()+.5,entity.getZ());
        caller.serverLevel().addFreshEntity(ball);
        try {
            var hit=EmptyPokeBallEntity.class.getDeclaredMethod("onHitEntity",EntityHitResult.class);
            hit.setAccessible(true);
            hit.invoke(ball,new EntityHitResult(entity));
        } catch (ReflectiveOperationException ex) { throw PokemonProbe.fail("Native capture hit: " + ex); }
        captureLockSeen=entity.isBusy() || entity.getBeamMode()!=0 || entity.getPhasingTargetId()!=-1;
        if (ball.getCapturingPokemon()!=entity || !captureLockSeen) throw PokemonProbe.fail("Native ball did not lock target");
    }
}

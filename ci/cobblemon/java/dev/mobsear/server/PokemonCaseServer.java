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
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.schedule.Activity;
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
    private final NamedCases named=new NamedCases();
    private Mob subject;
    private PokemonEntity witness;
    private EmptyPokeBallEntity ball;
    private PlayerPartyStore ownedStore;
    private Pokemon owned;
    private PokemonCase c;
    private int index, phase, age, aligned;
    private boolean live, reacted, fired, captureLockSeen, pathSeen, witnessReacted, offlineChecked, arranged, keptRoute;
    private long firstCall, gap;
    private double advance, nativeTravel;
    private BlockPos nativeDestination;
    private net.minecraft.world.level.pathfinder.Path interruptedRoute;
    private net.minecraft.world.level.pathfinder.Path initialPath;
    private WalkTarget initialWalk;
    private boolean preemptedPath, preemptedWalk;
    private int activeSamples;
    private net.minecraft.world.phys.Vec3 soundOrigin;
    private StringBuilder angleTrace;
    private int interruptionTick;
    private boolean released;

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
                PokemonRegistryAudit.check("server");
                for (var id : PokemonCase.requiredRegistryEntries())
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
        if (m.stage()==PokemonProbe.ARRANGE && p==caller && phase==PokemonProbe.FIRE && !arranged) {
            arranged=true;
            soundOrigin=caller.position();
            if (c==PokemonCase.OFFLINE_OWNER) checkOfflineOwner();
            if (c.ordinaryEntity()) named.arrange(c,subject,caller);
            if (c.accepts() || c==PokemonCase.COOLDOWN_HELD) prepareIdle(subject);
            if (c==PokemonCase.APPROACH || c==PokemonCase.INTERRUPTED)
                System.out.println("POKEMON_NATIVE_START "+c+" ground="+subject.onGround()+" navDone="+subject.getNavigation().isDone()+
                        " walkMemory="+subject.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)+" active="+subject.getBrain().getActiveNonCoreActivity());
            if (witness!=null) {
                prepareIdle(witness);
                System.out.println("POKEMON_FRESH_WITNESS species="+witness.getPokemon().getSpecies().getResourceIdentifier()
                        +" distance="+Math.round(witness.distanceTo(caller))+" present="+(caller.serverLevel().getEntity(witness.getId())==witness)
                        +" activity="+witness.getBrain().getActiveNonCoreActivity()+" wild="+witness.getPokemon().isWild()
                        +" busy="+witness.isBusy()+" health="+witness.getHealth()+" phasing="+witness.getPhasingTargetId());
            }
            if (c.walking()) startNativeWalk();
            return;
        }
        if (m.stage() == PokemonProbe.FIRE && p == caller && phase == PokemonProbe.FIRE && !fired) {
            fired = true;
            if (!arranged) soundOrigin=caller.position();
            if (c.accepts()) {
                var source=new PokemonProbe.Message(PokemonProbe.SOURCE,index,subject.getId(),soundOrigin.x,soundOrigin.z);
                PacketDistributor.sendToPlayer(caller,source);
                if (viewer!=caller) PacketDistributor.sendToPlayer(viewer,source);
            }
            if (c==PokemonCase.APPROACH || c==PokemonCase.INTERRUPTED) {
                initialPath=subject.getNavigation().getPath();
                initialWalk=subject.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
                System.out.println("POKEMON_NATIVE_ROUTE "+c+" assigned="+CobblemonCalls.hasResponsePath(subject)+
                        " navDestination="+(initialPath==null ? "none" : initialPath.getTarget())+" walkMemory="+(initialWalk!=null));
            }
            if (c.ordinaryEntity()) {
                named.afterWire(c,subject,caller);
                if (c.guardPicture()) {
                    var confirmation=new PokemonProbe.Message(PokemonProbe.VERIFIED_GUARD,index,subject.getId());
                    PacketDistributor.sendToPlayer(caller,confirmation);
                    if (viewer!=caller) PacketDistributor.sendToPlayer(viewer,confirmation);
                }
            }
            long now=p.serverLevel().getGameTime();
            if (c==PokemonCase.COOLDOWN_PRIME) firstCall=now;
            if (c.reusesSubject()) {
                gap=now-firstCall;
                if ((c==PokemonCase.COOLDOWN_HELD && (gap<45 || gap>=100)) || (c==PokemonCase.COOLDOWN_EXPIRED && gap<105))
                    throw PokemonProbe.fail("Unexpected repeated-call interval " + gap);
            }
            if (c.walking()) {
                var existing=subject.getNavigation().getPath();
                if (existing==null || existing.isDone() || !nativeDestination.equals(existing.getTarget()))
                    throw PokemonProbe.fail("Native walk was no longer live at the species call " + c);
            }
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
        chance.set(switch (c) {
            case APPROACH, BLOCKED, WALKING_PIKACHU, WALKING_EEVEE, INTERRUPTED -> 1d;
            case LOOK, COOLDOWN_PRIME, COOLDOWN_HELD, COOLDOWN_EXPIRED, PACK_FORM, PACK_CUSTOM, GLOBAL_PACK -> 0d;
            default -> .20d;
        });
        caller.teleportTo(world,c.distance+.5,Y,Z+.5,90,(float)Math.toDegrees(Math.atan2(1.15,c.distance)));
        caller.setDeltaMovement(0,0,0);
        caller.hurtMarked=true;
        if (viewer!=caller) viewer.teleportTo(world,3.5,Y+0.1,Z+5.5,148,9);
        if (c.ordinaryEntity()) {
            subject=named.spawn(c,caller);
        } else if (c == PokemonCase.PASSIVE) {
            subject=EntityType.COW.create(world);
        } else if (!c.reusesSubject()) {
            var entity = PokemonProperties.Companion.parse(c==PokemonCase.PACK_FORM
                    ? "oricorio dance_style=pom-pom level=15" : "pikachu level=15").createEntity(world);
            if (c==PokemonCase.PACK_FORM) c.checkTrackedForm(entity,"Spawn");
            else entity.getPokemon().setSpecies(PokemonSpecies.getByIdentifier(c.species));
            subject=entity;
            if (c==PokemonCase.OWNER) setOwner(caller.getUUID());
        }
        if (subject==null) throw PokemonProbe.fail("Could not create actual subject");
        subject.moveTo(.5,Y,Z+.5,90,0);
        subject.setPersistenceRequired();
        if (!c.reusesSubject() && !world.addFreshEntity(subject)) throw PokemonProbe.fail("Could not spawn actual subject");
        if (c==PokemonCase.COOLDOWN_HELD) {
            witness=PokemonProperties.Companion.parse("pikachu level=15").createEntity(world);
            witness.getPokemon().setSpecies(PokemonSpecies.getByIdentifier(c.species));
            witness.setPersistenceRequired();
            witness.moveTo(25.5,Y,Z+.5,90,0);
            if (!world.addFreshEntity(witness)) throw PokemonProbe.fail("No fresh cooldown witness");
        }
        if (c==PokemonCase.BLOCKED) wall(true);
        phase=PokemonProbe.BEFORE; age=aligned=0; reacted=fired=captureLockSeen=pathSeen=witnessReacted=offlineChecked=arranged=keptRoute=false;
        advance=nativeTravel=0; gap=0; nativeDestination=null; interruptedRoute=initialPath=null; initialWalk=null;
        released=preemptedPath=preemptedWalk=false; activeSamples=0;
        soundOrigin=caller.position(); angleTrace=new StringBuilder();
        acknowledgements.clear();
        announce();
    }
    private void announce() {
        var m = new PokemonProbe.Message(phase,index,subject.getId());
        System.out.println("POKEMON_STAGE " + c + " phase=" + phase + " players=" + (caller==viewer ? 1:2));
        PacketDistributor.sendToPlayer(caller,m);
        if (caller!=viewer) PacketDistributor.sendToPlayer(viewer,m);
    }
    private static void idle(Mob entity) {
        entity.getNavigation().stop();
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }
    private void setOwner(UUID owner) {
        ownedStore=Cobblemon.INSTANCE.getStorage().getParty(owner, caller.serverLevel().registryAccess());
        owned=((PokemonEntity)subject).getPokemon();
        if (!ownedStore.add(owned) || !owner.equals(owned.getOwnerUUID()) || owned.isWild())
            throw PokemonProbe.fail("Cobblemon party did not establish ownership " + c);
    }
    private void checkOfflineOwner() {
        // Cobblemon recalls a party member on its next tick after its owner goes offline.
        setOwner(UUID.fromString("b885e8b7-29b9-422d-a75c-29f4a7470827"));
        if (subject.isRemoved() || owned.getOwnerPlayer()!=null || caller.serverLevel().getEntity(subject.getId())!=subject)
            throw PokemonProbe.fail("Offline-owned subject missing at the live safety check");
        PokemonReactions.hear(caller,c.species,32, mob -> {
            if (mob==subject) throw PokemonProbe.fail("Offline owner's Pokémon accepted call");
            return 1;
        });
        offlineChecked=true;
    }
    private static void prepareIdle(Mob mob) {
        idle(mob);
        mob.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        mob.setDeltaMovement(0,0,0);
    }
    private void startNativeWalk() {
        nativeDestination=new BlockPos(0,Y,Z+6);
        var nav=subject.getNavigation();
        var route=nav.createPath(nativeDestination,0);
        if (route==null || !route.canReach() || !nav.moveTo(route,.8))
            throw PokemonProbe.fail("No native walking route for " + c);
        subject.getBrain().setMemory(MemoryModuleType.WALK_TARGET,new WalkTarget(nativeDestination,.8f,0));
    }
    private void wall(boolean place) {
        for (int dz=-7;dz<=7;dz++) for (int dy=0;dy<4;dy++)
            caller.serverLevel().setBlockAndUpdate(new BlockPos(2,Y+dy,Z+dz),(place ? Blocks.STONE : Blocks.AIR).defaultBlockState());
    }
    private void tick(ServerTickEvent.Post event) {
        if (!live) return;
        age++;
        if (age>1100) throw PokemonProbe.fail(c + " timed out, phase=" + phase + " fired=" + fired + " acks="
                + acknowledgements.size() + " reacted=" + reacted + " aligned=" + aligned + " forward=" + advance
                + " nativeTravel="+nativeTravel+" nativeRoute="+keptRoute+" gap="+gap+" witness="+witnessReacted
                + " activeTicks="+activeSamples+" headErrorByTick="+angleTrace+" last="+subjectState());
        int count=caller==viewer ? 1:2;
        if (phase==PokemonProbe.BEFORE) {
            if (subject.isRemoved()) throw PokemonProbe.fail("Native subject disappeared before call "+c+" reason="+subject.getRemovalReason()+" "+subjectState());
            if (age==100 || age==300) System.out.println("POKEMON_SERVER_TRACK "+c+" id="+subject.getId()+
                    " present="+(caller.serverLevel().getEntity(subject.getId())==subject)+" distance="+subject.distanceTo(caller)+
                    " callerRiding="+caller.isPassenger()+" guardianFlight="+caller.getPersistentData().contains("uwu.guardian_flight")+" "+subjectState()+
                    (c.ordinaryEntity()?" "+NamedCases.nativeState(c,subject):""));
            subject.moveTo(.5,Y,Z+.5,90,0);
            subject.setYHeadRot(90); subject.yBodyRot=90;
            idle(subject);
            if (witness!=null) {
                witness.moveTo(25.5,Y,Z+.5,90,0);
                prepareIdle(witness);
            }
            if (age<36 || acknowledgements.size()!=count) return;
            if (c==PokemonCase.COOLDOWN_EXPIRED && caller.serverLevel().getGameTime()-firstCall<110) return;
            if (c==PokemonCase.BATTLE) engageBattle();
            if (c==PokemonCase.CAPTURE) engageCapture();
            soundOrigin=caller.position();
            phase=PokemonProbe.FIRE;
            age=0; acknowledgements.clear(); announce();
            return;
        }
        boolean active=c.ordinaryEntity() ? NamedMobReactions.isReacting(subject) : PokemonReactions.isReacting(subject);
        if (witness!=null && PokemonReactions.isReacting(witness)) witnessReacted=true;
        if (!active && !fired && !arranged && c.controlled()) idle(subject);
        if (active && !c.accepts()) throw PokemonProbe.fail("Protected or wrong subject reacted: " + c);
        if (active) {
            reacted=true;
            activeSamples++;
            if (c==PokemonCase.APPROACH || c==PokemonCase.INTERRUPTED) {
                var nowPath=subject.getNavigation().getPath();
                var nowWalk=subject.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
                preemptedPath |= initialPath!=null && nowPath!=null && !initialPath.getTarget().equals(nowPath.getTarget());
                preemptedWalk |= nowWalk!=null && nowWalk!=initialWalk;
            }
            if (!c.ordinaryEntity()) pathSeen |= CobblemonCalls.hasResponsePath(subject);
            if (c.walking()) {
                var existing=subject.getNavigation().getPath();
                keptRoute |= existing!=null && nativeDestination.equals(existing.getTarget());
                nativeTravel=Math.max(nativeTravel,subject.getZ()-(Z+.5));
            }
            double yaw=Math.toDegrees(Math.atan2(soundOrigin.z-subject.getZ(),soundOrigin.x-subject.getX()))-90;
            double error=Math.abs(Mth.wrapDegrees(subject.getYHeadRot()-yaw));
            if (c.ordinaryEntity() && activeSamples<=22) angleTrace.append(age).append('=').append(Math.round(error)).append(',');
            if (error<30) aligned++;
            advance=Math.max(advance,subject.getX()-.5);
            if (subject.getTarget()!=null) throw PokemonProbe.fail("Species call assigned combat target");
            if (c==PokemonCase.INTERRUPTED && ball==null && pathSeen && aligned>=2 && advance>=.2 && !subject.getNavigation().isDone()) {
                interruptedRoute=subject.getNavigation().getPath();
                engageCapture();
                interruptionTick=age;
            }
        }
        if (c==PokemonCase.INTERRUPTED && ball!=null && age>interruptionTick) {
            var nav=subject.getNavigation();
            if (ball.getCapturingPokemon()!=subject || !((PokemonEntity)subject).isBusy()) throw PokemonProbe.fail("Lost ongoing capture");
            released=!active && (nav.isDone() || nav.getPath()!=interruptedRoute);
            if (!released && age-interruptionTick>=2) throw PokemonProbe.fail("Hearing kept control during a real capture");
        }
        if ((age==38 || age==65) && c.ordinaryEntity() && c.accepts() && (acknowledgements.size()!=count || aligned<2))
            System.out.println("ENTITY_FACING_WAIT "+c+" signed="+fired+" reacted="+reacted+" clientAcks="+acknowledgements.size()+
                    " activeTicks="+activeSamples+" aligned="+aligned+" headErrorByTick="+angleTrace+" "+subjectState()+" "+NamedCases.nativeState(c,subject));
        if (acknowledgements.size()!=count || !fired) return;
        if (c.accepts() && (!reacted || aligned<2)) {
            if (c.ordinaryEntity() && age>=65) throw PokemonProbe.fail(c+" accepted="+reacted+" activeTicks="+activeSamples+
                    " headErrorByTick="+angleTrace+" at="+subjectState());
            return;
        }
        if ((c==PokemonCase.LOOK || c==PokemonCase.BLOCKED) && pathSeen) throw PokemonProbe.fail("Unsafe or unexpected navigation " + c);
        if (c==PokemonCase.BLOCKED && advance>1.5) throw PokemonProbe.fail("Crossed solid wall " + advance);
        if (c==PokemonCase.APPROACH && (!pathSeen || advance<.55)) {
            if (age>=65) throw PokemonProbe.fail("Controlled approach travel="+advance+" ownPath="+pathSeen+
                    " activeTicks="+activeSamples+" changedDestination="+preemptedPath+" replacedWalk="+preemptedWalk);
            return;
        }
        if (c==PokemonCase.APPROACH && advance>4.8) throw PokemonProbe.fail("Approached beyond fixed short route");
        if (c.walking() && pathSeen) throw PokemonProbe.fail("Replaced a native walking route");
        if (c.walking() && (!keptRoute || nativeTravel<.12)) return;
        if (c==PokemonCase.OFFLINE_OWNER && !offlineChecked) throw PokemonProbe.fail("Missing live offline owner assertion");
        if (c==PokemonCase.COOLDOWN_HELD && !witnessReacted) {
            if (age>=50) throw PokemonProbe.fail("Fresh witness failed the accepted repeated-call interval "+gap);
            return;
        }
        if (c==PokemonCase.INTERRUPTED && !released) return;
        if (age < (c.controlled()?45:27)) return;
        if (c==PokemonCase.PACK_FORM) {
            c.checkTrackedForm((PokemonEntity)subject,"Server after call");
            System.out.println("POKEMON_PACK_FORM_SERVER id="+c.species+" form=Pom-Pom aspect=pom-pom-style aligned="+aligned);
        }
        System.out.printf(java.util.Locale.ROOT,"POKEMON_CASE_PASS %s species=%s radius=32 startDistance=%d reacted=%s aligned=%d forward=%.2f ownPath=%s nativeRoute=%s nativeTravel=%.2f captureLock=%s released=%s offlineGuard=%s gapTicks=%d freshWitness=%s%n",
                c,c.species,c.distance,reacted,aligned,advance,pathSeen,keptRoute,nativeTravel,captureLockSeen,released,offlineChecked,gap,witnessReacted);
        if (c==PokemonCase.BATTLE) {
            var battle=((PokemonEntity)subject).getBattle();
            if (battle==null) throw PokemonProbe.fail("Battle terminated before safety check");
            battle.stop();
        }
        if (ball!=null) { ball.discard(); ball=null; }
        if (c.ordinaryEntity()) named.finish(c,subject,reacted,aligned);
        if (witness!=null) { witness.discard(); witness=null; }
        if (c==PokemonCase.BLOCKED) wall(false);
        if (c!=PokemonCase.COOLDOWN_PRIME && c!=PokemonCase.COOLDOWN_HELD) subject.discard();
        if (ownedStore!=null) { ownedStore.remove(owned); ownedStore=null; owned=null; }
        boolean acoustic="1".equals(System.getenv("MOBSEAR_POKEMON_ACOUSTIC"));
        if (acoustic && c==PokemonCase.LOPUNNY_FAR && "1".equals(System.getenv("MOBSEAR_OWL_ACOUSTIC"))) {
            index=PokemonCase.OWL_GUARDIAN.ordinal();
            prepare(); return;
        }
        int limit=acoustic ? PokemonCase.LOPUNNY_FAR.ordinal()+1
                : "1".equals(System.getenv("MOBSEAR_POKEMON_PACK")) ? PokemonCase.values().length : PokemonCase.PACK_FORM.ordinal();
        if (++index<limit) prepare();
        else {
            live=false;
            System.out.println(caller==viewer ? "MOBSEAR_POKEMON_SOLO_PASS" : "MOBSEAR_POKEMON_MULTI_PASS");
        }
    }
    private String subjectState() {
        return "origin="+soundOrigin+" caller="+caller.position()+" alive="+caller.isAlive()+" subject="+subject.position()
                +" yaw="+subject.getYHeadRot()+" body="+subject.yBodyRot+" hurt="+subject.hurtTime+" aggressive="+subject.isAggressive()
                +" attackTarget="+(subject.getTarget()!=null)+" lastHurtBy="+(subject.getLastHurtByMob()!=null)
                +" panic="+subject.getBrain().hasMemoryValue(MemoryModuleType.IS_PANICKING);
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

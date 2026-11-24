package dev.mobsear.server;

import dev.mobsear.fixture.CalledPokemonVisit;

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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

final class PokemonCaseServer {
    private static final int Y=104, Z=8;
    private final Set<UUID> arrivals = new HashSet<>(), acknowledgements = new HashSet<>();
    private ServerPlayer caller, viewer;
    private final NamedCases named=new NamedCases();
    private WalkingDeer deerWalk;
    private CalledPokemonVisit visit;
    private VisitCareProof care;
    private VisitWindowProof feedingWindow;
    private Mob subject;
    private Mob firstListener;
    private UUID firstListenerId;
    private PokemonEntity witness;
    private EmptyPokeBallEntity ball;
    private PlayerPartyStore ownedStore;
    private Pokemon owned;
    private PokemonCase c;
    private int index, phase, age, aligned;
    private boolean live, reacted, fired, captureLockSeen, pathSeen, witnessReacted, offlineChecked, arranged, keptRoute;
    private long firstCall, gap;
    private long previousRequestTime;
    private double advance, nativeTravel;
    private net.minecraft.world.level.pathfinder.Path interruptedRoute;
    private net.minecraft.world.level.pathfinder.Path initialPath;
    private WalkTarget initialWalk;
    private boolean preemptedPath, preemptedWalk;
    private int activeSamples;
    private net.minecraft.world.phys.Vec3 soundOrigin;
    private StringBuilder angleTrace;
    private int interruptionTick;
    private boolean released;

    PokemonCaseServer() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.item.ItemTossEvent event)->{
            if (feedingWindow!=null) feedingWindow.tossed(event);
        });
    }

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
                for (int x=-20;x<=42;x++) for (int z=-7;z<=8;z++)
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
            if (deerWalk!=null) deerWalk.atCall(caller);
            else if (!c.walking() && (c.accepts())) prepareIdle(subject);
            if (c==PokemonCase.AGGRO) engageAggro();
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
            long requestTime=System.nanoTime();
            if (c==PokemonCase.GLOBAL_PACK) {
                var mob=(PokemonEntity)subject;
                System.out.println("POKEMON_BOXTAIL_SERVER stage=request elapsedSincePriorMs="+
                        (previousRequestTime==0 ? -1 : (requestTime-previousRequestTime)/1_000_000)+
                        " subjectReacting="+PokemonReactions.isReacting(mob)+" key="+mob.getPokemon().getSpecies().getResourceIdentifier()+
                        " distance="+mob.distanceTo(p)+" wild="+mob.getPokemon().isWild()+" sleeping="+mob.isSleeping()+
                        " busy="+mob.isBusy()+" active="+mob.getBrain().getActiveNonCoreActivity()+" owner="+(mob.getOwnerUUID()!=null)+
                        " battle="+mob.isBattling()+" beam="+mob.getBeamMode()+" phasing="+mob.getPhasingTargetId()+
                        " mutedOrDeafened="+NearbyMics.inputBlocked(p)+" "+subjectState());
            }
            previousRequestTime=requestTime;
            if (c==PokemonCase.REPEAT_FIRST) {
                firstCall=now;
                firstListener=subject;
                firstListenerId=subject.getUUID();
            }
            if (c.reusesSubject()) {
                if (subject!=firstListener || subject!=caller.serverLevel().getEntity(firstListenerId)
                        || !((PokemonEntity)subject).getPokemon().isWild())
                    throw PokemonProbe.fail("The repeat call did not reach the same living wild Pokémon");
                gap=now-firstCall;
                if ((c==PokemonCase.REPEAT_QUICK && (gap<45 || gap>=100)) || (c==PokemonCase.REPEAT_WALK && gap<105))
                    throw PokemonProbe.fail("Unexpected repeated-call interval " + gap);
            }
            if (c.walking() && (visit==null || !PokemonReactions.isReacting(subject) || !subject.getNavigation().isDone()))
                throw PokemonProbe.fail("Active wandering Pokémon did not stop at the species call "+c);
            if (c==PokemonCase.AGGRO && (PokemonReactions.isReacting(subject) || subject.getTarget()!=caller))
                throw PokemonProbe.fail("Calling an aggressive Pokémon disturbed its target");
            return;
        }
        int expected = phase == PokemonProbe.BEFORE ? PokemonProbe.BEFORE : PokemonProbe.SEEN;
        if (m.stage()!=expected || !acknowledgements.add(p.getUUID())) throw PokemonProbe.fail("Unordered barrier " + m);
    }

    private void prepare() {
        c = PokemonCase.values()[index];
        care = null;
        feedingWindow = null;
        deerWalk = null;
        visit = null;
        var world = caller.serverLevel();
        caller.setGameMode(c == PokemonCase.CREATIVE_GEODUDE ? GameType.CREATIVE : GameType.SURVIVAL);
        double callX=c.reusesSubject() ? subject.getX()+c.distance : c.distance+.5;
        double callZ=c.reusesSubject() ? subject.getZ() : Z+.5;
        caller.teleportTo(world,callX,Y,callZ,90,(float)Math.toDegrees(Math.atan2(1.15,c.distance)));
        caller.setDeltaMovement(0,0,0);
        caller.hurtMarked=true;
        if (viewer!=caller) {
            // A closer eligible player must not steal the walking Eevee from its moving caller.
            viewer.setGameMode(c==PokemonCase.WALKING_EEVEE ? GameType.ADVENTURE : GameType.SPECTATOR);
            viewer.teleportTo(world,3.5,Y+0.1,Z+5.5,148,9);
        }
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
        if (!c.reusesSubject()) subject.moveTo(.5,Y,Z+.5,90,0);
        subject.setPersistenceRequired();
        if (!c.reusesSubject() && !world.addFreshEntity(subject)) throw PokemonProbe.fail("Could not spawn actual subject");
        if (c==PokemonCase.REPEAT_QUICK) {
            witness=PokemonProperties.Companion.parse("pikachu level=15").createEntity(world);
            witness.getPokemon().setSpecies(PokemonSpecies.getByIdentifier(c.species));
            witness.setPersistenceRequired();
            witness.moveTo(25.5,Y,Z+.5,90,0);
            if (!world.addFreshEntity(witness)) throw PokemonProbe.fail("No fresh repeat-call witness");
        }
        if (c == PokemonCase.CREATIVE_GEODUDE) {
            witness = PokemonProperties.Companion.parse("geodude alolan=true level=15").createEntity(world);
            var ordinary = ((PokemonEntity) subject).getPokemon();
            var regional = witness.getPokemon();
            if (ordinary.getForm() == regional.getForm() || !regional.getAspects().contains("alolan")
                    || ordinary.getAspects().contains("alolan") || ordinary.getSpecies() != regional.getSpecies())
                throw PokemonProbe.fail("Expected distinct regular and Alolan forms of the same Geodude species");
            witness.setPersistenceRequired();
            witness.moveTo(2.5, Y, Z + 2.5, 90, 0);
            if (!world.addFreshEntity(witness)) throw PokemonProbe.fail("Missing Alolan Geodude");
        }
        if (c==PokemonCase.BLOCKED) wall(true);
        phase=PokemonProbe.BEFORE; age=aligned=0; reacted=fired=captureLockSeen=pathSeen=witnessReacted=offlineChecked=arranged=keptRoute=false;
        advance=nativeTravel=0; gap=0; interruptedRoute=initialPath=null; initialWalk=null;
        released=preemptedPath=preemptedWalk=false; activeSamples=interruptionTick=0;
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
        PokemonReactions.hear(caller,c.species,32);
        if (PokemonReactions.isReacting(subject))
            throw PokemonProbe.fail("Offline owner's Pokémon accepted call");
        offlineChecked=true;
    }
    private static void prepareIdle(Mob mob) {
        idle(mob);
        mob.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        mob.setDeltaMovement(0,0,0);
    }
    private void engageAggro() {
        subject.setTarget(caller);
        subject.setAggressive(true);
        subject.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET,caller);
        if (subject.getTarget()!=caller || !subject.isAggressive()) throw PokemonProbe.fail("Missing native aggression");
    }
    private void wall(boolean place) {
        for (int x=-2;x<=2;x++) for(int dz=-2;dz<=2;dz++) for (int dy=0;dy<=4;dy++)
            if (Math.abs(x)==2 || Math.abs(dz)==2 || dy==4)
                caller.serverLevel().setBlockAndUpdate(new BlockPos(x,Y+dy,Z+dz),(place ? Blocks.STONE : Blocks.AIR).defaultBlockState());
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
            if (deerWalk==null && visit==null && !c.reusesSubject()) {
                subject.moveTo(.5,Y,Z+.5,90,0);
                subject.setYHeadRot(90); subject.yBodyRot=90;
                idle(subject);
            }
            if (witness!=null) {
                if (c==PokemonCase.CREATIVE_GEODUDE) witness.moveTo(2.5,Y,Z+2.5,90,0);
                else witness.moveTo(25.5,Y,Z+.5,90,0);
                witness.setYHeadRot(90);
                prepareIdle(witness);
            }
            if (age<36 || acknowledgements.size()!=count) return;
            if (c.walking()) {
                if (visit==null) {
                    int stroll=c==PokemonCase.REPEAT_WALK ? (subject.getZ()>Z+.5 ? -6 : 6) : 8;
                    visit=new CalledPokemonVisit((PokemonEntity)subject,caller,stroll);
                }
                if (!visit.isWandering()) {
                    if (age>180) throw PokemonProbe.fail("Native Pokémon did not walk before the call "+c);
                    return;
                }
            }
            if (c==PokemonCase.WALKING_DEER) {
                if (deerWalk==null) deerWalk=new WalkingDeer((net.minecraft.world.entity.PathfinderMob)subject);
                if (!deerWalk.started()) {
                    if (age>120) throw PokemonProbe.fail("Deer did not begin its native stroll before the call");
                    return;
                }
            }
            if (c==PokemonCase.REPEAT_WALK && caller.serverLevel().getGameTime()-firstCall<110) return;
            if (c==PokemonCase.BATTLE) engageBattle();
            if (c==PokemonCase.BATTLE || c==PokemonCase.OWNER) VisitCareProof.excluded((PokemonEntity)subject,caller,c.name());
            if (c==PokemonCase.CAPTURE) engageCapture();
            soundOrigin=caller.position();
            phase=PokemonProbe.FIRE;
            age=0; acknowledgements.clear(); announce();
            return;
        }
        boolean active=c.ordinaryEntity() ? NamedMobReactions.isReacting(subject) : PokemonReactions.isReacting(subject);
        if (c==PokemonCase.GLOBAL_PACK && fired && (age==8 || age==18 || age==36)) {
            double yaw=Math.toDegrees(Math.atan2(soundOrigin.z-subject.getZ(),soundOrigin.x-subject.getX()))-90;
            System.out.println("POKEMON_BOXTAIL_SERVER stage=pose tick="+age+" reacting="+active+" alignedTicks="+aligned+
                    " headError="+Math.round(Math.abs(Mth.wrapDegrees(subject.getYHeadRot()-yaw)))+
                    " sleep="+subject.isSleeping()+" active="+subject.getBrain().getActiveNonCoreActivity());
        }
        if (deerWalk!=null && fired) deerWalk.observe(active);
        if (witness!=null && PokemonReactions.isReacting(witness)) {
            double yaw=Math.toDegrees(Math.atan2(caller.getZ()-witness.getZ(), caller.getX()-witness.getX()))-90;
            if (c!=PokemonCase.CREATIVE_GEODUDE || Math.abs(Mth.wrapDegrees(witness.getYHeadRot()-yaw))<30)
                witnessReacted=true;
        }
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
            double yaw=Math.toDegrees(Math.atan2(soundOrigin.z-subject.getZ(),soundOrigin.x-subject.getX()))-90;
            double error=Math.abs(Mth.wrapDegrees(subject.getYHeadRot()-yaw));
            if (c.ordinaryEntity() && activeSamples<=22) angleTrace.append(age).append('=').append(Math.round(error)).append(',');
            if (error<30) aligned++;
            advance=Math.max(advance,subject.getX()-.5);
            if (subject.getTarget()!=null && !(c==PokemonCase.NEW_AGGRO && interruptionTick>0))
                throw PokemonProbe.fail("Species call assigned combat target");
            if (c==PokemonCase.INTERRUPTED && ball==null && pathSeen && aligned>=2 && advance>=.2 && !subject.getNavigation().isDone()) {
                interruptedRoute=subject.getNavigation().getPath();
                engageCapture();
                interruptionTick=age;
            }
            if (c==PokemonCase.NEW_AGGRO && interruptionTick==0 && pathSeen && advance>.3) {
                engageAggro();
                interruptionTick=age;
            }
        }
        if (c==PokemonCase.NEW_AGGRO && interruptionTick>0 && age==interruptionTick+2) {
            if (active) throw PokemonProbe.fail("Ongoing approach failed to yield to combat");
            released=true;
        }
        boolean visitDone=visit!=null && fired && visit.observe(active,c==PokemonCase.WALKING_EEVEE,age,
                CobblemonCalls.visiting((PokemonEntity)subject,caller));
        if (visitDone && Math.abs(visit.stationarySamples()-120)>1)
            throw PokemonProbe.fail("Expected six seconds of physical rest from arrival, sampled ticks="+visit.stationarySamples());
        if (c==PokemonCase.WALKING_PIKACHU && fired) {
            if (care==null) care=new VisitCareProof((PokemonEntity)subject,caller,viewer,c);
            care.tick();
            if (visitDone && !care.finished) return;
        }
        if (fired && (c==PokemonCase.WALKING_EEVEE || c==PokemonCase.WALKING_LOPUNNY)) {
            if (feedingWindow==null) feedingWindow=new VisitWindowProof((PokemonEntity)subject,caller,viewer,c);
            feedingWindow.tick();
            if (visitDone && !feedingWindow.finished()) return;
        }
        if (visitDone) released=true;
        if (c==PokemonCase.INTERRUPTED && ball!=null && age>interruptionTick) {
            var nav=subject.getNavigation();
            if (ball.getCapturingPokemon()!=subject || !((PokemonEntity)subject).isBusy()) throw PokemonProbe.fail("Lost ongoing capture");
            released=!active && (nav.isDone() || nav.getPath()!=interruptedRoute);
            if (!released && age-interruptionTick>=2) throw PokemonProbe.fail("Hearing kept control during a real capture");
        }
        if ((age==38 || age==65) && c.ordinaryEntity() && c.accepts() && (acknowledgements.size()!=count || aligned<2))
            System.out.println("ENTITY_FACING_WAIT "+c+" signed="+fired+" reacted="+reacted+" clientAcks="+acknowledgements.size()+
                    " activeTicks="+activeSamples+" aligned="+aligned+" headErrorByTick="+angleTrace+" "+subjectState()+" "+NamedCases.nativeState(c,subject));
        if (deerWalk!=null && age==65 && acknowledgements.size()!=count)
            throw PokemonProbe.fail("Moving deer never visibly faced the speaker in every rendered view: "+acknowledgements.size()+"/"+count);
        if (acknowledgements.size()!=count || !fired) return;
        if (c.accepts() && (!reacted || aligned<2)) {
            if (c.ordinaryEntity() && age>=65) throw PokemonProbe.fail(c+" accepted="+reacted+" activeTicks="+activeSamples+
                    " headErrorByTick="+angleTrace+" at="+subjectState());
            return;
        }
        if (c==PokemonCase.BLOCKED && (advance>1.5 || active)) {
            if (advance>1.5 || age>300) throw PokemonProbe.fail("Pokémon crossed the enclosure or never released an unreachable route");
            return;
        }
        if (c==PokemonCase.APPROACH && (!pathSeen || advance<.55)) {
            if (age>=65) throw PokemonProbe.fail("Controlled approach travel="+advance+" ownPath="+pathSeen+
                    " activeTicks="+activeSamples+" changedDestination="+preemptedPath+" replacedWalk="+preemptedWalk);
            return;
        }
        if (c.walking() && !visitDone) return;
        if (c==PokemonCase.OFFLINE_OWNER && !offlineChecked) throw PokemonProbe.fail("Missing live offline owner assertion");
        if (c == PokemonCase.CREATIVE_GEODUDE && (!caller.isCreative() || !witnessReacted))
            throw PokemonProbe.fail("One Creative base-name call did not turn both forms of Geodude");
        if (c==PokemonCase.REPEAT_QUICK && !witnessReacted) {
            if (age>=50) throw PokemonProbe.fail("Fresh witness failed the accepted repeated-call interval "+gap);
            return;
        }
        if ((c==PokemonCase.INTERRUPTED || c==PokemonCase.NEW_AGGRO) && !released) return;
        if (age < (c.controlled()?45:27)) return;
        if (c==PokemonCase.REPEAT_FIRST && active) {
            // End this visit by leaving range. The next call must succeed as soon as the visit has released.
            if (caller.distanceToSqr(subject)<=32*32) caller.teleportTo(caller.getX()+48,caller.getY(),caller.getZ());
            return;
        }
        if (c.reusesSubject()) System.out.printf(java.util.Locale.ROOT,
                "POKEMON_REPEAT_PASS sameWildEntity=true sinceFirstCallTicks=%d accepted=%s completedVisit=%s witness=%s%n",
                gap,reacted,visitDone,witnessReacted);
        if (deerWalk!=null) {
            if (!deerWalk.verify()) return;
            nativeTravel=deerWalk.travelDuring();
            keptRoute=true;
        }
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
        if (c!=PokemonCase.REPEAT_FIRST && c!=PokemonCase.REPEAT_QUICK) subject.discard();
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

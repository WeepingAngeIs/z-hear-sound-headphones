package dev.mobsear.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.GameRules;

/** Real registered entities in the same disposable server and clients as the species tests. */
final class NamedCases {
    private Mob companion, offender;
    private boolean preserved;

    Mob spawn(PokemonCase c,ServerPlayer caller) {
        var level=caller.serverLevel();
        level.getServer().setDifficulty(Difficulty.NORMAL,true);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer());
        caller.setHealth(caller.getMaxHealth());
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(c.species).orElseThrow(() -> PokemonProbe.fail("Missing actual entity "+c.species));
        if (!(type.create(level) instanceof Mob subject)) throw PokemonProbe.fail("No actual mob for "+c.species);
        if (c.mimicCase()) {
            if (!net.neoforged.fml.ModList.get().isLoaded("cumimic")) throw PokemonProbe.fail("Missing native Dungeon Mimic mod");
            // Keep native navigation and vision running. Allied fixture entities cannot acquire one another as combat targets.
            var board=level.getScoreboard();
            var team=board.getPlayerTeam("hear_mimic");
            if (team==null) team=board.addPlayerTeam("hear_mimic");
            board.addPlayerToTeam(caller.getScoreboardName(),team);
            board.addPlayerToTeam(subject.getScoreboardName(),team);
            // Shade the sample using real blocks; the native disguise retains its own equipment.
            for (int x=-3;x<=3;x++) for (int z=5;z<=11;z++) level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x,111,z),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            if (c.nativeMimic()) { subject.addTag("cu_mimic"); subject.addTag("cu_floor_1"); }
            else if (c==PokemonCase.NAMED_ONLY_ZOMBIE) subject.setCustomName(net.minecraft.network.chat.Component.literal("Dungeon Mimic"));
        }
        if (c==PokemonCase.BROAD_COW || c==PokemonCase.BOSS_FIGHTING || c==PokemonCase.NAGA_COMBAT || c==PokemonCase.OWL_OFFENDER) {
            if (c==PokemonCase.OWL_OFFENDER) {
                var keeper=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("uwudimension","petal_keeper");
                companion=(Mob)BuiltInRegistries.ENTITY_TYPE.getOptional(keeper).orElseThrow().create(level);
            } else companion=c==PokemonCase.BROAD_COW ? EntityType.ZOMBIE.create(level) : EntityType.IRON_GOLEM.create(level);
            companion.moveTo(3.5,104,11.5,0,0); companion.setPersistenceRequired();
            if (!level.addFreshEntity(companion)) throw PokemonProbe.fail("No live combat/broad witness for "+c);
            if (c==PokemonCase.OWL_OFFENDER) {
                offender=EntityType.IRON_GOLEM.create(level);
                offender.moveTo(16.5,104,13.5,0,0);
                offender.setPersistenceRequired();
                if (!level.addFreshEntity(offender)) throw PokemonProbe.fail("Missing independent Guardian offender");
            }
        }
        preserved=false;
        return subject;
    }
    void arrange(PokemonCase c,Mob subject,ServerPlayer caller) {
        if (c.mimicCase()) {
            var target=subject.getTarget();
            boolean marked=subject.getTags().contains("cu_mimic"), disguised=subject.getPersistentData().hasUUID("cu_mimic_fake");
            boolean allied=subject.isAlliedTo(caller), expected=c.nativeMimic();
            var details=c+" marked="+marked+" nativeDisguise="+disguised+" callerAllied="+allied+" callerDistance="+subject.distanceTo(caller)+
                    " callerVisible="+subject.hasLineOfSight(caller)+" target="+(target==null?"none":BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()));
            System.out.println("ENTITY_MIMIC_SETUP "+details);
            if (target!=null || marked!=expected || disguised!=expected || !allied) throw PokemonProbe.fail("Wrong Mimic setup "+details);
            if (c==PokemonCase.ZOMBIE_CALL_MIMIC) {
                var zombies=caller.serverLevel().getEntitiesOfClass(Mob.class,caller.getBoundingBox().inflate(32),
                        mob -> mob.getType()==EntityType.ZOMBIE && mob.isAlive() && mob.distanceToSqr(caller)<=32*32);
                if (zombies.size()!=1 || zombies.get(0)!=subject) throw PokemonProbe.fail("Expected only the native Mimic in range: "+zombies);
            }
        } else if (c==PokemonCase.BOSS_CHARGING) ((WitherBoss)subject).makeInvulnerable();
        else if (c==PokemonCase.BOSS_FIGHTING || c==PokemonCase.NAGA_COMBAT) subject.setTarget(companion);
        else if (c==PokemonCase.BROAD_COW) companion.setTarget(null);
        else if (c==PokemonCase.OWL_OFFENDER) {
            // A genuine attack on a nearby Keeper activates the Guardian's duty without hurting it.
            if (!companion.hurt(subject.damageSources().mobAttack(offender),2f) || !offender.getUUID().equals(read(subject,"offender"))
                    || subject.hurtTime!=0 || subject.getLastHurtByMob()!=null)
                throw PokemonProbe.fail("Keeper did not notify the unhurt Guardian about its attacker");
        } else if (c==PokemonCase.VELVET_GUARD) {
            var nbt=subject.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            nbt.putInt("CourtBeat",140);
            subject.load(nbt);
            if (!Boolean.TRUE.equals(read(subject,"guarding"))) throw PokemonProbe.fail("Real Velvet Vesper did not enter its saved guard phase");
        } else if (c==PokemonCase.VELVET_IDLE && (subject.getTarget()!=null || Boolean.TRUE.equals(read(subject,"guarding"))
                || Boolean.TRUE.equals(read(subject,"folding")) || Boolean.TRUE.equals(read(subject,"casting")))) {
            throw PokemonProbe.fail("The positive Velvet Vesper trial started during an encounter");
        }
    }
    void afterWire(PokemonCase c,Mob subject,ServerPlayer caller) {
        if (c==PokemonCase.BOSS_CHARGING) {
            preserved=((WitherBoss)subject).getInvulnerableTicks()>0;
        } else if (c==PokemonCase.BOSS_FIGHTING || c==PokemonCase.NAGA_COMBAT) {
            preserved=companion!=null && subject.getTarget()==companion;
        } else if (c==PokemonCase.BROAD_COW) {
            preserved=companion!=null && companion.getTarget()==caller && subject.getTarget()==null;
        } else if (c==PokemonCase.OWL_OFFENDER) {
            preserved=offender.getUUID().equals(read(subject,"offender"));
        } else if (c==PokemonCase.VELVET_GUARD) {
            preserved=Boolean.TRUE.equals(read(subject,"guarding"));
        } else return;
        if (!preserved) throw PokemonProbe.fail("Named/broad call disrupted actual state for "+c);
    }
    void finish(PokemonCase c,Mob subject,boolean reacted,int aligned) {
        if (c.mimicCase() && subject.getTarget()!=null) throw PokemonProbe.fail("Mimic call changed a zombie's target "+c);
        if (c==PokemonCase.BOSS_CHARGING && ((WitherBoss)subject).getInvulnerableTicks()<=0)
            throw PokemonProbe.fail("Wither left its native charge");
        System.out.println("ENTITY_NAME_CASE_PASS "+c+" id="+c.species+" spoken="+c.spoken()+" reacted="+reacted+
                " aligned="+aligned+" protectedOrBroadWitness="+preserved);
        if (subject.level() instanceof net.minecraft.server.level.ServerLevel level) {
            if (c.mimicCase()) {
                var board=level.getScoreboard();
                board.removePlayerTeam(board.getPlayerTeam("hear_mimic"));
            }
            var projectiles=level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    subject.getBoundingBox().inflate(128),shot -> shot.getOwner()==subject || (companion!=null && shot.getOwner()==companion));
            projectiles.forEach(net.minecraft.world.entity.Entity::discard);
            if (!projectiles.isEmpty()) System.out.println("ENTITY_CASE_CLEANUP "+c+" projectiles="+projectiles.size());
        }
        if (companion!=null) { companion.discard(); companion=null; }
        if (offender!=null) { offender.discard(); offender=null; }
    }
    static String nativeState(PokemonCase c,Mob subject) {
        return switch (c) {
            case OWL_GUARDIAN, OWL_OFFENDER -> "offender="+read(subject,"offender")+" chasing="+read(subject,"chasing")+
                    " gripping="+read(subject,"gripping")+" throwing="+read(subject,"throwing");
            case VELVET_IDLE, VELVET_GUARD -> "guard="+read(subject,"guarding")+" folding="+read(subject,"folding")+" casting="+read(subject,"casting");
            default -> "sleep="+subject.isSleeping()+" invulnerable="+subject.isInvulnerable()+" riding="+subject.isPassenger()+" vehicle="+subject.isVehicle();
        };
    }
    private static Object read(Mob subject,String name) {
        try { return subject.getClass().getMethod(name).invoke(subject); }
        catch (ReflectiveOperationException e) { throw PokemonProbe.fail("Missing actual "+subject.getType()+" state "+name); }
    }
}

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
    private Mob companion;
    private boolean preserved;

    Mob spawn(PokemonCase c,ServerPlayer caller) {
        var level=caller.serverLevel();
        level.getServer().setDifficulty(Difficulty.NORMAL,true);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer());
        caller.setHealth(caller.getMaxHealth());
        var type=BuiltInRegistries.ENTITY_TYPE.getOptional(c.species).orElseThrow(() -> PokemonProbe.fail("Missing actual entity "+c.species));
        if (!(type.create(level) instanceof Mob subject)) throw PokemonProbe.fail("No actual mob for "+c.species);
        if (c==PokemonCase.BROAD_COW || c==PokemonCase.BOSS_FIGHTING) {
            companion=c==PokemonCase.BROAD_COW ? EntityType.ZOMBIE.create(level) : EntityType.IRON_GOLEM.create(level);
            companion.moveTo(3.5,104,11.5,0,0); companion.setPersistenceRequired();
            if (!level.addFreshEntity(companion)) throw PokemonProbe.fail("No live combat/broad witness for "+c);
        }
        preserved=false;
        return subject;
    }
    void arrange(PokemonCase c,Mob subject) {
        if (c==PokemonCase.BOSS_CHARGING) ((WitherBoss)subject).makeInvulnerable();
        else if (c==PokemonCase.BOSS_FIGHTING) subject.setTarget(companion);
        else if (c==PokemonCase.BROAD_COW) companion.setTarget(null);
    }
    void afterWire(PokemonCase c,Mob subject,ServerPlayer caller) {
        if (c==PokemonCase.BOSS_CHARGING) {
            preserved=((WitherBoss)subject).getInvulnerableTicks()>0;
        } else if (c==PokemonCase.BOSS_FIGHTING) {
            preserved=companion!=null && subject.getTarget()==companion;
        } else if (c==PokemonCase.BROAD_COW) {
            preserved=companion!=null && companion.getTarget()==caller && subject.getTarget()==null;
        } else return;
        if (!preserved) throw PokemonProbe.fail("Named/broad call disrupted actual state for "+c);
    }
    void finish(PokemonCase c,Mob subject,boolean reacted,int aligned) {
        if (c==PokemonCase.BOSS_CHARGING && ((WitherBoss)subject).getInvulnerableTicks()<=0)
            throw PokemonProbe.fail("Wither left its native charge");
        System.out.println("ENTITY_NAME_CASE_PASS "+c+" id="+c.species+" spoken="+c.spoken()+" reacted="+reacted+
                " aligned="+aligned+" protectedOrBroadWitness="+preserved);
        if (companion!=null) { companion.discard(); companion=null; }
    }
}

package dev.mobsear.smoke;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Two real Survival players. The friend draws the first attack through ordinary Minecraft AI. */
final class DistractionSmoke {
    static final int CALL=-200, PHOTO=-220;
    static final List<String> CASES=List.of("skeletons","zombie","creeper","mobs");
    static final int X=0, Y=108, Z=265;
    private static final List<Mob> group=new ArrayList<>();
    private static final Set<UUID> faced=new HashSet<>(), arrows=new HashSet<>();
    private static ServerPlayer caller, friend;
    private static int index, age, previousHit;
    private static boolean requested, transferred, image, attack;

    static boolean handles(int value) {
        return (value<=CALL && value>CALL-CASES.size()) || (value<=PHOTO && value>PHOTO-CASES.size());
    }

    static boolean expected(int trial, EntityType<?> type) {
        return switch(trial) {
            case 0 -> type==EntityType.SKELETON;
            case 1 -> type==EntityType.ZOMBIE;
            case 2 -> type==EntityType.CREEPER;
            default -> type==EntityType.SKELETON || type==EntityType.ZOMBIE;
        };
    }

    static int calledCount(int trial) { return trial==0 ? 2 : trial==3 ? 3 : 1; }

    static void start(ServerPlayer speaker, ServerPlayer victim) {
        caller=speaker;
        friend=victim;
        var level=speaker.serverLevel();
        level.getServer().setDifficulty(Difficulty.NORMAL,true);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer());
        level.setDayTime(18000);
        for(int x=-9;x<=53;x++) for(int z=-8;z<=21;z++)
            level.setBlockAndUpdate(new BlockPos(X+x,Y-1,Z+z),Blocks.SEA_LANTERN.defaultBlockState());
        NeoForge.EVENT_BUS.addListener(DistractionSmoke::tick);
        begin();
    }

    private static void begin() {
        var level=caller.serverLevel();
        group.clear(); faced.clear(); arrows.clear();
        requested=transferred=image=attack=false;
        age=0;
        for(var player:List.of(friend,caller)) {
            player.setGameMode(GameType.SURVIVAL);
            player.setHealth(player.getMaxHealth());
        }
        caller.teleportTo(level,X+49.5,Y,Z+.5,90,0);
        friend.teleportTo(level,X+.5,Y,Z+12.5,180,6);
        previousHit=caller.getLastHurtByMobTimestamp();
        if (index==0 || index==3) {
            spawn(EntityType.SKELETON,-3,-2);
            spawn(EntityType.SKELETON,2,1);
            spawn(EntityType.ZOMBIE,-5,1);
        } else if (index==1) spawn(EntityType.ZOMBIE,0,0);
        else spawn(EntityType.CREEPER,0,0);
    }

    private static void spawn(EntityType<? extends Mob> type,int dx,int dz) {
        var level=caller.serverLevel();
        var mob=type.create(level);
        if(mob==null) throw SmokeControl.failure("No distraction subject for "+type);
        mob.moveTo(X+dx+.5,Y,Z+dz+.5,0,0);
        mob.setPersistenceRequired();
        if(type==EntityType.SKELETON) mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));
        if(!level.addFreshEntity(mob)) throw SmokeControl.failure("Failed to spawn distraction subject");
        group.add(mob);
    }

    static void photographed(ServerPlayer player,int value) {
        if (player!=friend || value!=PHOTO-index || !transferred || image) throw SmokeControl.failure("Unexpected distraction photo");
        image=true;
    }

    private static void tick(ServerTickEvent.Post event) {
        if(index==CASES.size()) return;
        if(++age>400 || group.stream().anyMatch(mob->!mob.isAlive()))
            throw SmokeControl.failure("Distraction "+CASES.get(index)+" age="+age+" requested="+requested+" transfer="+transferred
                    +" faced="+faced.size()+" photo="+image+" attack="+attack+" targets="
                    +group.stream().map(m->m.getTarget()==caller?"caller":m.getTarget()==friend?"friend":"other").toList());
        var level=caller.serverLevel();
        var nearby=level.getEntitiesOfClass(AbstractArrow.class,friend.getBoundingBox().inflate(40));
        for(var arrow:nearby) {
            if (!arrows.add(arrow.getUUID()) || !transferred || !(arrow.getOwner() instanceof Mob shooter) || !group.contains(shooter)) continue;
            Vec3 path=arrow.getDeltaMovement().multiply(1,0,1).normalize();
            Vec3 toCaller=caller.position().subtract(shooter.position()).multiply(1,0,1).normalize();
            Vec3 toFriend=friend.position().subtract(shooter.position()).multiply(1,0,1).normalize();
            if(path.dot(toCaller)>.88 && path.dot(toFriend)<.78 && shooter.getTarget()==caller) attack=true;
        }
        if (!requested) {
            if (age<10 || !group.stream().allMatch(m->m.getTarget()==friend) ||
                    group.stream().noneMatch(m->m.isAggressive() || !m.getNavigation().isDone())) return;
            caller.teleportTo(level,X+13.5,Y,Z+.5,90,4);
            System.out.println("MOBSEAR_DISTRACTION_BEFORE "+CASES.get(index)+" nativeTargetsOnFriend="+group.size());
            requested=true;
            PacketDistributor.sendToPlayer(caller,new SmokeControl.Barrier(CALL-index));
            return;
        }
        boolean correct=true;
        for (var mob:group) {
            boolean selected=expected(index,mob.getType());
            if (mob.getTarget()!=(selected ? caller : friend)) correct=false;
            if (!selected || mob.getTarget()!=caller) continue;
            double yaw=Math.toDegrees(Math.atan2(caller.getZ()-mob.getZ(),caller.getX()-mob.getX()))-90;
            if(Math.abs(Mth.wrapDegrees(mob.getYHeadRot()-yaw))<=30) faced.add(mob.getUUID());
            if(index==1 && caller.getLastHurtByMob()==mob && caller.getLastHurtByMobTimestamp()!=previousHit) attack=true;
            if(index==2 && mob instanceof Creeper c && c.getSwellDir()>0 && !c.isIgnited() && c.distanceTo(caller)<4) attack=true;
        }
        if(correct && !transferred) {
            transferred=true;
            PacketDistributor.sendToPlayer(friend,new SmokeControl.Barrier(PHOTO-index));
        }
        if(!transferred || !correct || !image || !attack || faced.size()!=calledCount(index)) return;
        String action=index==1 ? "melee-hit-on-caller" : index==2 ? "approached-and-started-native-fuse" : "native-arrow-aimed-at-caller";
        System.out.println("MOBSEAR_DISTRACTION_PASS "+CASES.get(index)+" previous=friend callerTargets="+faced.size()
                +" stillOnFriend="+(group.size()-faced.size())+" action="+action+" observerPicture=true");
        group.forEach(Mob::discard);
        nearby.forEach(AbstractArrow::discard);
        if(++index<CASES.size()) begin();
        else {
            friend.setGameMode(GameType.CREATIVE);
            System.out.println("MOBSEAR_MULTIPLAYER_PASS");
        }
    }
}

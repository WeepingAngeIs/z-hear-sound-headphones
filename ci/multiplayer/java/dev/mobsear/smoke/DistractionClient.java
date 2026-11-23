package dev.mobsear.smoke;

import dev.mobsear.client.NoticeFixture;
import dev.mobsear.net.Cue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.network.PacketDistributor;

final class DistractionClient {
    private static int photo=-1, awaiting=-1;
    private static String lastNotice="";

    static void receive(int value,boolean sender) {
        if (value<=DistractionSmoke.PHOTO) {
            if(!sender) photo=DistractionSmoke.PHOTO-value;
            return;
        }
        if(!sender) return;
        try {
            int index=DistractionSmoke.CALL-value;
            var path=Path.of(System.getenv("MOBSEAR_CUE_PROOF")).resolveSibling("recognized-distractions.txt");
            var calls=Files.readAllLines(path);
            if(!calls.equals(List.of("SKELETON","ZOMBIE","CREEPER","MOBS"))) throw new IllegalStateException("Unexpected Windows decisions");
            NoticeFixture.clear();
            GazeClient.supplyDecision(Cue.valueOf(calls.get(index)));
            awaiting=index;
            lastNotice="";
        } catch(Exception ex) { throw SmokeControl.failure("Distraction voice proof "+ex); }
    }

    static void tick() {
        if(awaiting>=0) {
            String text=NoticeFixture.text();
            if(!text.isEmpty() && !text.equals(lastNotice)) {
                lastNotice=text;
                System.out.println("MOBSEAR_DISTRACTION_NOTICE "+DistractionSmoke.CASES.get(awaiting)+" "+text);
                var listeners=switch(awaiting) {
                    case 0 -> List.of("skeleton");
                    case 1 -> List.of("zombie");
                    case 2 -> List.of("creeper");
                    default -> List.of("skeleton","zombie");
                };
                if(listeners.stream().anyMatch(name->text.equals(Component.translatable("mobsear.speech.heard",
                        Component.translatable("entity.minecraft."+name)).getString()))) {
                    PacketDistributor.sendToServer(new SmokeControl.Barrier(DistractionSmoke.HEARD-awaiting));
                    awaiting=-1;
                }
            }
        }
        if(photo<0) return;
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || mc.screen!=null) return;
        var speaker=mc.level.players().stream().filter(p->p.getGameProfile().getName().equals(SmokeControl.SPEAKER)).findFirst().orElse(null);
        if(speaker==null) return;
        int seen=0;
        for(var entity:mc.level.entitiesForRendering()) {
            if(!(entity instanceof Mob mob) || !DistractionSmoke.expected(photo,mob.getType()) || mob.distanceToSqr(mc.player)>30*30) continue;
            double yaw=Math.toDegrees(Math.atan2(speaker.getZ()-mob.getZ(),speaker.getX()-mob.getX()))-90;
            if(Math.abs(Mth.wrapDegrees(mob.getYHeadRot()-yaw))<40) seen++;
        }
        if(seen<DistractionSmoke.calledCount(photo)) return;
        GazeClient.capture("distraction-"+DistractionSmoke.CASES.get(photo));
        PacketDistributor.sendToServer(new SmokeControl.Barrier(DistractionSmoke.PHOTO-photo));
        photo=-1;
    }
}

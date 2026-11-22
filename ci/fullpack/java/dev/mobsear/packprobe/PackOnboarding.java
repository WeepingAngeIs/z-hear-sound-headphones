package dev.mobsear.packprobe;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/** Complete the pack's normal first-join screens for the one new, hosted test player. */
final class PackOnboarding {
    private final Set<String> chosen = new HashSet<>();
    private int titleTicks;
    private boolean opened, declined, tutorial, created;
    private final boolean fresh="1".equals(System.getenv("MOBSEAR_NATIVE_WORLD"));

    void tick(Minecraft mc) throws ReflectiveOperationException {
        if (mc.screen==null) { titleTicks=0; return; }
        String screen=mc.screen.getClass().getName();
        if (mc.player==null) {
            if (!declined && screen.equals("xaero.lib.client.gui.GuiUpdateAll")) {
                for (var child:mc.screen.children()) if (child instanceof Button button && button.active && button.visible
                        && CommonComponents.GUI_NO.equals(button.getMessage())) {
                    declined=true;
                    button.onPress();
                    System.out.println("ACOUSTIC_ONBOARDING declined optional map updater");
                    return;
                }
            }
            if (!opened && mc.screen instanceof TitleScreen title && mc.getOverlay()==null) {
                if (++titleTicks==80) {
                    opened=true;
                    if (fresh) {
                        CreateWorldScreen.openFresh(mc,title);
                        System.out.println("ACOUSTIC_ONBOARDING starting native world creation with pack registries");
                    } else {
                        mc.createWorldOpenFlows().openWorld("HearPublicPack", ()->mc.setScreen(title));
                        System.out.println("ACOUSTIC_ONBOARDING opened the generated test save from title");
                    }
                }
            } else titleTicks=0;
            if (fresh && !created && mc.screen instanceof CreateWorldScreen create) {
                var state=create.getUiState();
                state.setName("HearPublicPack");
                state.setSeed("123");
                state.setDifficulty(Difficulty.NORMAL);
                state.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,null);
                var label=Component.translatable("selectWorld.create").getString();
                for (var child:create.children()) if (child instanceof Button b && b.visible && b.active && b.getMessage().getString().equals(label)) {
                    created=true;
                    b.onPress();
                    System.out.println("ACOUSTIC_ONBOARDING created new native Survival world");
                    break;
                }
            }
            return;
        }
        if (screen.equals("com.cyberday1.neoorigins.screen.OriginSelectionScreen")) {
            Object picker=mc.screen;
            Object presenter=Boundary.get(picker,"presenter");
            Object layer=presenter.getClass().getMethod("currentLayer").invoke(presenter);
            if (layer==null) return;
            String layerId=layer.getClass().getMethod("id").invoke(layer).toString();
            String choice=switch(layerId) {
                case "neoorigins:origin" -> "neoorigins:human";
                case "neoorigins:class" -> "neoorigins:class_warrior";
                default -> throw new IllegalStateException("Unexpected origin layer "+layerId);
            };
            var id=ResourceLocation.parse(choice);
            if (!((List<?>)presenter.getClass().getMethod("allOriginIds").invoke(presenter)).contains(id)) return;
            if (chosen.add(choice)) {
                var select=picker.getClass().getDeclaredMethod("selectOrigin",ResourceLocation.class);
                var confirm=picker.getClass().getDeclaredMethod("confirmSelection");
                select.setAccessible(true);
                confirm.setAccessible(true);
                select.invoke(picker,id);
                confirm.invoke(picker);
                System.out.println("ACOUSTIC_ONBOARDING confirmed "+choice);
            }
        } else if (!tutorial && screen.equals("dev.unyielding.quests.client.tutorial.TutorialScreen")) {
            tutorial=true;
            mc.screen.onClose(); // The normal Later button.
            System.out.println("ACOUSTIC_ONBOARDING deferred first-join tutorial");
        }
    }
}

package com.tutorialschematic.client;

import com.tutorialschematic.client.build.BuildRunner;
import com.tutorialschematic.client.screen.MainMenuScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Горячие клавиши редактора.
 *
 * <p>Во время съёмки лезть в чат за командой неудобно, поэтому всё, что нужно
 * нажимать по ходу дела — пауза, откат, смена режима — висит на клавишах.
 */
public final class Keybinds {

    private static final String CATEGORY = "Tutorial Schematic";

    private static KeyMapping openMenu;
    private static KeyMapping toggleMarkup;
    private static KeyMapping cycleMode;
    private static KeyMapping toggleHighlights;
    private static KeyMapping pauseBuild;

    private Keybinds() {
    }

    public static void register() {
        openMenu = bind("Открыть меню", GLFW.GLFW_KEY_G);
        toggleMarkup = bind("Разметка вкл/выкл", GLFW.GLFW_KEY_B);
        cycleMode = bind("Сменить режим выделения", GLFW.GLFW_KEY_V);
        toggleHighlights = bind("Подсветка вкл/выкл", GLFW.GLFW_KEY_H);
        pauseBuild = bind("Пауза постройки", GLFW.GLFW_KEY_P);

        ClientTickEvents.END_CLIENT_TICK.register(Keybinds::tick);
    }

    private static KeyMapping bind(String name, int key) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(name, InputConstants.Type.KEYSYM, key, KeyMapping.Category.MISC));
    }

    private static void tick(Minecraft client) {
        EditorState state = EditorState.get();

        // меню открывается всегда: создать первую схему тоже нужно откуда-то
        while (openMenu.consumeClick()) {
            client.setScreenAndShow(new MainMenuScreen());
        }

        while (toggleMarkup.consumeClick()) {
            state.setMarkupEnabled(!state.markupEnabled());
            EditorState.actionBar("Разметка: " + (state.markupEnabled() ? "вкл" : "выкл"));
        }

        while (cycleMode.consumeClick()) {
            state.cycleMode();
            EditorState.actionBar("Режим: " + state.mode().displayName() + " — " + state.mode().hint());
        }

        while (toggleHighlights.consumeClick()) {
            state.setHighlightsVisible(!state.highlightsVisible());
            EditorState.actionBar("Подсветка: " + (state.highlightsVisible() ? "вкл" : "выкл"));
        }

        while (pauseBuild.consumeClick()) {
            BuildRunner.get().togglePause();
        }
    }
}

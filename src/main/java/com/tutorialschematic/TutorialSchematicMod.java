package com.tutorialschematic;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Общая точка входа. Работает и на встроенном сервере одиночной игры — именно оттуда
 * исполнитель постройки ставит блоки в мир.
 */
public class TutorialSchematicMod implements ModInitializer {

    public static final String MOD_ID = "tutorialschematic";
    public static final Logger LOGGER = LoggerFactory.getLogger("TutorialSchematic");

    @Override
    public void onInitialize() {
        LOGGER.info("Tutorial Schematic загружен");
    }
}

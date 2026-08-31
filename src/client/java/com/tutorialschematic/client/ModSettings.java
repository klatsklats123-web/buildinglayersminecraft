package com.tutorialschematic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorialschematic.TutorialSchematicMod;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки мода, общие для всех схем.
 *
 * <p>Задержки в начале и в конце слоя задаются у каждого слоя отдельно, но выставлять их
 * руками в каждом новом слое утомительно. Здесь лежат значения по умолчанию: новый слой
 * берёт их себе, а кнопкой в настройках их можно разом применить ко всем слоям открытой
 * схемы.
 *
 * <p>Файл лежит рядом с настройками игры и не привязан ни к миру, ни к схеме: это привычки
 * автора, а не свойство постройки.
 */
public final class ModSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModSettings instance;

    /** Задержка перед первым блоком нового слоя, в тиках. */
    private int defaultStartDelayTicks = 20;
    /** Задержка после последнего блока нового слоя, в тиках. */
    private int defaultEndDelayTicks = 20;

    /**
     * Время суток, на котором держать запись при экспорте; -1 — не трогать.
     *
     * <p>Постройка идёт минутами, и за это время солнце успевает уйти: тени разворачиваются,
     * яркость плывёт внутри одного ролика. Полдень выбран по умолчанию потому, что при нём
     * меньше всего резких теней на стенах.
     */
    private int replayTimeOfDay = 6000;

    private ModSettings() {
    }

    public static ModSettings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public int defaultStartDelayTicks() {
        return defaultStartDelayTicks;
    }

    public void setDefaultStartDelayTicks(int ticks) {
        this.defaultStartDelayTicks = clamp(ticks);
    }

    public int defaultEndDelayTicks() {
        return defaultEndDelayTicks;
    }

    public void setDefaultEndDelayTicks(int ticks) {
        this.defaultEndDelayTicks = clamp(ticks);
    }

    public int replayTimeOfDay() {
        return replayTimeOfDay;
    }

    /** Сутки — 24000 тиков. Всё, что вне их, считаем за «не трогать». */
    public void setReplayTimeOfDay(int time) {
        this.replayTimeOfDay = time < 0 || time >= 24000 ? -1 : time;
    }

    private static int clamp(int ticks) {
        return Math.max(0, Math.min(20 * 60, ticks));
    }

    // ---- файл ----

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("tutorial-schematic.json");
    }

    private static ModSettings load() {
        ModSettings settings = new ModSettings();
        Path path = file();
        if (!Files.exists(path)) {
            return settings;
        }
        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("defaultStartDelayTicks")) {
                settings.setDefaultStartDelayTicks(json.get("defaultStartDelayTicks").getAsInt());
            }
            if (json.has("defaultEndDelayTicks")) {
                settings.setDefaultEndDelayTicks(json.get("defaultEndDelayTicks").getAsInt());
            }
            if (json.has("replayTimeOfDay")) {
                settings.setReplayTimeOfDay(json.get("replayTimeOfDay").getAsInt());
            }
        } catch (Exception e) {
            // Битый файл настроек не повод не запускаться: берём значения по умолчанию.
            TutorialSchematicMod.LOGGER.warn("Не удалось прочитать настройки: {}", e.getMessage());
        }
        return settings;
    }

    public void save() {
        JsonObject json = new JsonObject();
        json.addProperty("defaultStartDelayTicks", defaultStartDelayTicks);
        json.addProperty("defaultEndDelayTicks", defaultEndDelayTicks);
        json.addProperty("replayTimeOfDay", replayTimeOfDay);
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.error("Не удалось сохранить настройки: {}", e.getMessage());
        }
    }
}

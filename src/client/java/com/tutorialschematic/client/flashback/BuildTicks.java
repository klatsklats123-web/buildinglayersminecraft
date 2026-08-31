package com.tutorialschematic.client.flashback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.camera.LayerShots;
import com.tutorialschematic.camera.MeasuredTiming;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Снимает время постройки со счётчика записи и хранит его между сессиями.
 *
 * <p>Сам счёт живёт в {@link MeasuredTiming}; здесь только съём тиков, файл и стык с игрой.
 *
 * <p>Метки в записи при этом остаются: во-первых, они видны на таймлайне и по ним удобно
 * ориентироваться руками, во-вторых, замеры сверяются с ними при чтении — не сошлось, значит
 * журнал не от этой записи, и время считается по-старому.
 */
public final class BuildTicks {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final MeasuredTiming TIMING = new MeasuredTiming();

    /**
     * Счётчик записи, снятый с клиентского потока.
     *
     * <p>Постройка идёт на потоке сервера, а счётчик живёт у клиента, и читать его оттуда
     * напрямую нельзя. Поэтому клиентский тик кладёт значение сюда, а постройка забирает
     * готовое. Ценой этому — расхождение не больше чем в тик, ровно такое же, какое и так
     * есть между метками и кладкой.
     */
    private static volatile int currentTick = -1;

    private BuildTicks() {
    }

    /** Вызывается с клиентского потока каждый тик. */
    public static void poll() {
        currentTick = FlashbackBridge.recordingTick();
    }

    public static void reset() {
        TIMING.reset();
    }

    public static void layerStarted(int layerIndex) {
        int tick = currentTick;
        if (tick >= 0) {
            TIMING.layerStarted(layerIndex, tick);
        }
    }

    public static void stepPlaced(int layerIndex) {
        int tick = currentTick;
        if (tick >= 0) {
            TIMING.stepPlaced(layerIndex, tick);
        }
    }

    public static void buildFinished() {
        if (currentTick >= 0) {
            TIMING.setEndTick(currentTick);
        }
        save();
    }

    /** Есть ли вообще замеры — чтобы было что сказать в чат. */
    public static boolean hasMeasurements() {
        return !TIMING.isEmpty();
    }

    /**
     * Переставляет куски слоя на замеренное время; при несовпадении оставляет как есть
     * и пишет причину в журнал.
     */
    public static List<LayerShots.Unit> retime(int layerIndex, List<LayerShots.Unit> units,
                                               int firstGlobalStep, int totalSteps,
                                               int markerTick, int layerEnd) {
        String mismatch = TIMING.mismatch(layerIndex, totalSteps, markerTick);
        if (mismatch != null) {
            if (!TIMING.isEmpty()) {
                TutorialSchematicMod.LOGGER.info("Слой {}: {} — время пойдёт по меткам",
                        layerIndex + 1, mismatch);
            }
            return units;
        }
        return TIMING.retime(layerIndex, units, firstGlobalStep, totalSteps, markerTick, layerEnd);
    }

    // ---- хранение ----

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("tutorial-schematic-timing.json");
    }

    /**
     * Кладёт журнал на диск. Экспорт обычно идёт в той же сессии, но выйти из игры между
     * записью и расстановкой камер — обычное дело, и терять из-за этого точное время жалко.
     */
    public static void save() {
        JsonArray array = new JsonArray();
        for (Map.Entry<Integer, MeasuredTiming.LayerLog> entry : TIMING.layers().entrySet()) {
            JsonArray ticks = new JsonArray();
            for (int tick : entry.getValue().stepTicks()) {
                ticks.add(tick);
            }
            JsonObject layer = new JsonObject();
            layer.addProperty("index", entry.getKey());
            layer.addProperty("start", entry.getValue().startTick());
            layer.add("steps", ticks);
            array.add(layer);
        }

        JsonObject root = new JsonObject();
        root.add("layers", array);
        root.addProperty("endTick", TIMING.endTick());

        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.warn("Журнал времени не сохранён: {}", e.getMessage());
        }
    }

    /** Читает журнал с диска, если в памяти его нет. */
    public static void loadIfEmpty() {
        if (!TIMING.isEmpty()) {
            return;
        }
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var element : root.getAsJsonArray("layers")) {
                JsonObject layer = element.getAsJsonObject();
                List<Integer> ticks = new ArrayList<>();
                for (var tick : layer.getAsJsonArray("steps")) {
                    ticks.add(tick.getAsInt());
                }
                TIMING.putLayer(layer.get("index").getAsInt(), layer.get("start").getAsInt(), ticks);
            }
            if (root.has("endTick")) {
                TIMING.setEndTick(root.get("endTick").getAsInt());
            }
        } catch (Exception e) {
            TIMING.reset();
            TutorialSchematicMod.LOGGER.warn("Журнал времени не прочитан: {}", e.getMessage());
        }
    }
}

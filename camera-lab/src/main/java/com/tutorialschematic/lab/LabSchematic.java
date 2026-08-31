package com.tutorialschematic.lab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorialschematic.order.BlockOrderer;
import com.tutorialschematic.order.OrderConfig;
import com.tutorialschematic.order.Pos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Схема, загруженная без Minecraft.
 *
 * <p>Мод читает {@code .ltutorial} через {@code TutorialSchematic}, а тот завязан на
 * {@code BlockPos} и на игровые классы блоков. Лаборатории всё это не нужно: для подбора
 * камер важны только координаты и порядок появления. Поэтому здесь свой загрузчик —
 * маленький и без зависимостей, но <b>очередь постройки он считает настоящим</b>
 * {@link BlockOrderer}, тем же самым, что исполняет игра.
 *
 * <p>Тики в реальном моде берутся из меток реплея. Записи тут нет, поэтому они считаются из
 * настроек темпа самого слоя ({@link OrderConfig#ticksPerStep()} и паузы после слоя) — то
 * есть ровно так, как их и задумывал автор схемы.
 */
public final class LabSchematic {

    /** Один слой: как называется, чем красится и в каком порядке встаёт. */
    public record Layer(String name, int colour, List<Pos> blocks, List<List<Pos>> steps,
                        int startTick, int endTick) {
    }

    private final String name;
    private final List<Layer> layers;
    private final List<Pos> everything;
    private final List<List<Pos>> allSteps;
    private final int[] stepTicks;

    private LabSchematic(String name, List<Layer> layers, List<Pos> everything,
                         List<List<Pos>> allSteps, int[] stepTicks) {
        this.name = name;
        this.layers = layers;
        this.everything = everything;
        this.allSteps = allSteps;
        this.stepTicks = stepTicks;
    }

    public String name() {
        return name;
    }

    public List<Layer> layers() {
        return layers;
    }

    /** Все блоки схемы разом — по ним считается габарит постройки. */
    public List<Pos> everything() {
        return everything;
    }

    /** Порядок появления блоков по всей записи подряд: шаг — блоки, встающие одновременно. */
    public List<List<Pos>> allSteps() {
        return allSteps;
    }

    /** Тик начала каждого шага плюс один элемент на конец последнего. */
    public int[] stepTicks() {
        return stepTicks;
    }

    public int totalTicks() {
        return stepTicks.length == 0 ? 0 : stepTicks[stepTicks.length - 1];
    }

    /** Какому слою принадлежит шаг с этим глобальным индексом. */
    public Layer layerOfStep(int globalStep) {
        int seen = 0;
        for (Layer layer : layers) {
            seen += layer.steps().size();
            if (globalStep < seen) {
                return layer;
            }
        }
        return layers.isEmpty() ? null : layers.get(layers.size() - 1);
    }

    public static LabSchematic load(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();

        JsonArray originJson = root.getAsJsonArray("origin");
        int ox = originJson.get(0).getAsInt();
        int oy = originJson.get(1).getAsInt();
        int oz = originJson.get(2).getAsInt();

        String name = root.has("name") ? root.get("name").getAsString() : path.getFileName().toString();

        List<Layer> layers = new ArrayList<>();
        List<Pos> everything = new ArrayList<>();
        List<List<Pos>> allSteps = new ArrayList<>();
        List<Integer> ticks = new ArrayList<>();

        int clock = 1;
        for (var element : root.getAsJsonArray("layers")) {
            JsonObject layerJson = element.getAsJsonObject();
            String layerName = layerJson.has("name") ? layerJson.get("name").getAsString() : "слой";
            int colour = layerJson.has("color") ? layerJson.get("color").getAsInt() : 0xAAAAAA;

            List<Pos> blocks = new ArrayList<>();
            JsonArray blocksJson = layerJson.getAsJsonArray("blocks");
            // формат: по четыре числа на блок —x, y, z относительно origin, и номер в палитре
            for (int i = 0; i + 3 < blocksJson.size(); i += 4) {
                blocks.add(new Pos(
                        blocksJson.get(i).getAsInt() + ox,
                        blocksJson.get(i + 1).getAsInt() + oy,
                        blocksJson.get(i + 2).getAsInt() + oz));
            }
            if (blocks.isEmpty()) {
                continue;
            }

            OrderConfig order = layerJson.has("order")
                    ? OrderConfig.fromJson(layerJson.getAsJsonObject("order"))
                    : new OrderConfig();
            List<Pos> seeds = new ArrayList<>();
            if (layerJson.has("seeds")) {
                JsonArray seedsJson = layerJson.getAsJsonArray("seeds");
                for (int i = 0; i + 2 < seedsJson.size(); i += 3) {
                    seeds.add(new Pos(
                            seedsJson.get(i).getAsInt() + ox,
                            seedsJson.get(i + 1).getAsInt() + oy,
                            seedsJson.get(i + 2).getAsInt() + oz));
                }
            }

            List<List<Pos>> steps = BlockOrderer.orderIntoSteps(blocks, order, seeds);
            if (steps.isEmpty()) {
                continue;
            }

            int perStep = Math.max(1, order.ticksPerStep());
            int pause = layerJson.has("pauseAfterTicks") ? layerJson.get("pauseAfterTicks").getAsInt() : 20;

            int layerStart = clock;
            if (ticks.isEmpty()) {
                ticks.add(layerStart);
            }
            for (int i = 0; i < steps.size(); i++) {
                clock += perStep;
                ticks.add(clock);
            }
            int layerEnd = clock;
            clock += pause;

            everything.addAll(blocks);
            allSteps.addAll(steps);
            layers.add(new Layer(layerName, colour, blocks, steps, layerStart, layerEnd));
        }

        int[] stepTicks = new int[ticks.size()];
        for (int i = 0; i < stepTicks.length; i++) {
            stepTicks[i] = ticks.get(i);
        }
        return new LabSchematic(name, layers, everything, allSteps, stepTicks);
    }
}

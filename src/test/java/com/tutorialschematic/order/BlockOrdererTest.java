package com.tutorialschematic.order;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockOrdererTest {

    /** Сплошной параллелепипед — удобная модель стены или пола. */
    private static List<Pos> box(int sizeX, int sizeY, int sizeZ) {
        List<Pos> list = new ArrayList<>();
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    list.add(new Pos(x, y, z));
                }
            }
        }
        return list;
    }

    @Test
    void keepsEveryBlockExactlyOnce() {
        List<Pos> blocks = box(4, 3, 5);
        List<Pos> ordered = BlockOrderer.order(blocks, OrderConfig.of("rand"));
        assertEquals(blocks.size(), ordered.size());
        assertEquals(new java.util.HashSet<>(blocks), new java.util.HashSet<>(ordered), "блоки не должны теряться или дублироваться");
    }

    @Test
    void bottomUpFillsLayerByLayer() {
        List<Pos> ordered = BlockOrderer.order(box(3, 4, 3), OrderConfig.of("y"));
        int previous = -1;
        for (Pos pos : ordered) {
            assertTrue(pos.y() >= previous, "высота не должна убывать");
            previous = pos.y();
        }
        assertEquals(0, ordered.get(0).y());
        assertEquals(3, ordered.get(ordered.size() - 1).y());
    }

    @Test
    void descendingFlagReversesTheLevel() {
        OrderConfig config = OrderConfig.of("y");
        config.key(0).setDescending(true);
        List<Pos> ordered = BlockOrderer.order(box(3, 4, 3), config);
        assertEquals(3, ordered.get(0).y());
        assertEquals(0, ordered.get(ordered.size() - 1).y());
    }

    @Test
    void secondKeyBreaksTiesOfTheFirst() {
        // Сначала высота, потом угол: внутри каждого ряда обход по кругу
        OrderConfig config = OrderConfig.of("y", "a");
        List<Pos> ordered = BlockOrderer.order(box(3, 2, 3), config);

        List<Pos> firstRow = ordered.subList(0, 9);
        for (Pos pos : firstRow) {
            assertEquals(0, pos.y(), "первым должен пройти весь нижний ряд");
        }
    }

    @Test
    void twoStripsMeetInTheMiddle() {
        // min(x, X-1-x) даёт одинаковый ключ у зеркальных краёв
        List<Pos> ordered = BlockOrderer.order(box(5, 1, 1), OrderConfig.of("min(x, X-1-x)"));
        assertEquals(0, ordered.get(0).x());
        assertEquals(4, ordered.get(1).x(), "второй блок — с противоположного края");
        assertEquals(2, ordered.get(4).x(), "середина строится последней");
    }

    @Test
    void resultIsDeterministicAcrossRuns() {
        List<Pos> blocks = box(4, 4, 4);
        OrderConfig config = OrderConfig.of("rand");

        List<Pos> first = BlockOrderer.order(blocks, config);
        Collections.shuffle(blocks, new java.util.Random(7));
        List<Pos> second = BlockOrderer.order(blocks, config);

        assertEquals(first, second, "порядок не должен зависеть от того, как блоки лежали в наборе");
    }

    @Test
    void changingSeedChangesRandomOrder() {
        List<Pos> blocks = box(4, 4, 4);
        OrderConfig a = OrderConfig.of("rand");
        OrderConfig b = OrderConfig.of("rand");
        b.setSeed(777);
        assertNotEquals(BlockOrderer.order(blocks, a), BlockOrderer.order(blocks, b));
    }

    @Test
    void brokenFormulaFallsBackToStableOrderInsteadOfCrashing() {
        OrderConfig config = OrderConfig.of("y +");
        assertTrue(config.firstError() != null, "ошибка должна быть видна в настройке");

        List<Pos> blocks = box(3, 3, 3);
        List<Pos> ordered = BlockOrderer.order(blocks, config);
        assertEquals(blocks.size(), ordered.size(), "постройка всё равно должна работать");
    }

    @Test
    void validKeysStillApplyWhenOneIsBroken() {
        OrderConfig config = OrderConfig.of("y", "не формула");
        List<Pos> ordered = BlockOrderer.order(box(3, 3, 3), config);
        assertEquals(0, ordered.get(0).y());
        assertEquals(2, ordered.get(ordered.size() - 1).y());
    }

    @Test
    void emptyAndSingleBlockLayersAreHandled() {
        assertEquals(0, BlockOrderer.order(List.of(), OrderConfig.of("y")).size());
        assertEquals(1, BlockOrderer.order(List.of(new Pos(1, 1, 1)), OrderConfig.of("y")).size());
    }

    @Test
    void stepsSplitTheQueueIntoBatches() {
        OrderConfig config = OrderConfig.of("y");
        config.setBatchSize(4);
        List<Pos> ordered = BlockOrderer.order(box(3, 1, 3), config);

        List<List<Pos>> steps = BlockOrderer.steps(ordered, config);
        assertEquals(3, steps.size(), "9 блоков по 4 — это три шага");
        assertEquals(4, steps.get(0).size());
        assertEquals(1, steps.get(2).size(), "последний шаг может быть неполным");
    }

    @Test
    void presetsProduceUsableOrderings() {
        List<Pos> blocks = box(5, 3, 5);
        for (OrderPresets.Preset preset : OrderPresets.all()) {
            OrderConfig config = preset.toConfig();
            assertTrue(config.isValid(), "пресет «" + preset.name() + "»: " + config.firstError());
            assertEquals(blocks.size(), BlockOrderer.order(blocks, config).size(),
                    "пресет «" + preset.name() + "» потерял блоки");
        }
    }

    @Test
    void configSurvivesJsonRoundTrip() {
        OrderConfig config = OrderConfig.of("y", "a");
        config.key(1).setDescending(true);
        config.setBatchSize(7);
        config.setTicksPerStep(5);
        config.setSeed(4242);

        OrderConfig restored = OrderConfig.fromJson(config.toJson());
        assertEquals(2, restored.keys().size());
        assertEquals("a", restored.key(1).source());
        assertTrue(restored.key(1).descending());
        assertEquals(7, restored.batchSize());
        assertEquals(5, restored.ticksPerStep());
        assertEquals(4242, restored.seed());
    }

    @Test
    void lastSortLevelCannotBeRemoved() {
        OrderConfig config = OrderConfig.of("y");
        assertTrue(!config.removeKey(0), "иначе сортировать стало бы нечем");
        assertEquals(1, config.keys().size());
    }
}

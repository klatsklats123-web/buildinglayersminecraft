package com.tutorialschematic.order;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раскадровка «по фронту»: за шаг встают все блоки с одинаковым значением формулы,
 * поэтому ширина шага берётся из самой фигуры, а не из настройки.
 */
class FrontStepTest {

    private static OrderConfig config(String formula, boolean frontStep, int batchSize) {
        OrderConfig config = new OrderConfig();
        config.keys().clear();
        config.keys().add(new SortKey(formula));
        config.setFrontStep(frontStep);
        config.setBatchSize(batchSize);
        return config;
    }

    /** Y-образная фигура: одна нитка снизу, выше расходится на две. */
    private static List<Pos> branching() {
        List<Pos> blocks = new ArrayList<>();
        blocks.add(new Pos(0, 0, 0));
        blocks.add(new Pos(0, 1, 0));
        for (int y = 2; y < 5; y++) {
            blocks.add(new Pos(-(y - 1), y, 0));
            blocks.add(new Pos(y - 1, y, 0));
        }
        return blocks;
    }

    @Test
    void ширинаШагаПовторяетФигуру() {
        List<List<Pos>> steps = BlockOrderer.orderIntoSteps(
                branching(), config("y", true, 1), List.of());

        // ствол по одному блоку, потом развилка — сразу обе ветки
        assertEquals(List.of(1, 1, 2, 2, 2),
                steps.stream().map(List::size).toList());
    }

    @Test
    void поСчётуШиринаПостояннаяИФигуруНеВидит() {
        List<List<Pos>> steps = BlockOrderer.orderIntoSteps(
                branching(), config("y", false, 1), List.of());

        // тот же набор, но развилка раскладывается по одному блоку за шаг
        assertEquals(8, steps.size());
        assertTrue(steps.stream().allMatch(step -> step.size() == 1));
    }

    @Test
    void двеВеткиИдутОдновременноПоРасстояниюОтСтарта() {
        // кольцо: от одной точки фронт расходится в обе стороны и смыкается напротив
        List<Pos> ring = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                if (x == 0 || x == 3 || z == 0 || z == 3) {
                    ring.add(new Pos(x, 0, z));
                }
            }
        }
        List<List<Pos>> steps = BlockOrderer.orderIntoSteps(
                ring, config("d", true, 1), List.of(new Pos(0, 0, 0)));

        assertEquals(1, steps.get(0).size(), "старт — один блок");
        assertTrue(steps.get(1).size() >= 2, "дальше фронт расходится в обе стороны");
    }

    @Test
    void дробнаяФормулаВыраждаетсяВОдинБлок() {
        // у таких формул совпадений почти не бывает — режим не должен склеивать близкое
        List<Pos> line = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            line.add(new Pos(x, 0, x));
        }
        List<List<Pos>> steps = BlockOrderer.orderIntoSteps(
                line, config("x + sin(z*40)*3", true, 1), List.of());

        assertEquals(6, steps.size());
        assertTrue(steps.stream().allMatch(step -> step.size() == 1));
    }

    @Test
    void всеБлокиПопадаютРовноВОдинШаг() {
        List<Pos> blocks = branching();
        for (boolean front : new boolean[]{true, false}) {
            List<List<Pos>> steps = BlockOrderer.orderIntoSteps(
                    blocks, config("y", front, 3), List.of());
            int total = steps.stream().mapToInt(List::size).sum();
            assertEquals(blocks.size(), total, "ни один блок не потерялся и не задвоился");
        }
    }
}

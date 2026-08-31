package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MeasuredTimingTest {

    /** Кусок из стольких-то шагов, начинающийся с такого-то шага слоя. */
    private static LayerShots.Unit unit(int firstStep, int steps) {
        List<List<Pos>> stepList = new ArrayList<>();
        List<Pos> blocks = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            Pos pos = new Pos(firstStep + i, 0, 0);
            stepList.add(List.of(pos));
            blocks.add(pos);
        }
        return new LayerShots.Unit(blocks, stepList, LayerShots.Form.BY_SIDE,
                LayerShots.Facing.EAST, 0, 1, firstStep);
    }

    /** Журнал слоя, где шаги шли ровно через два тика от {@code start}. */
    private static MeasuredTiming evenly(int layer, int start, int steps) {
        MeasuredTiming timing = new MeasuredTiming();
        timing.layerStarted(layer, start);
        for (int i = 0; i < steps; i++) {
            timing.stepPlaced(layer, start + 10 + i * 2);
        }
        return timing;
    }

    @Test
    void границыКусковБерутсяИзЗамеров() {
        MeasuredTiming timing = evenly(0, 100, 10);
        List<LayerShots.Unit> units = List.of(unit(0, 4), unit(4, 3), unit(7, 3));

        List<LayerShots.Unit> retimed = timing.retime(0, units, 0, 10, 100, 300);

        // шаг i встал на тике 110 + 2i
        assertEquals(110, retimed.get(0).startTick());
        assertEquals(118, retimed.get(0).endTick());
        assertEquals(118, retimed.get(1).startTick());
        assertEquals(124, retimed.get(1).endTick());
        assertEquals(124, retimed.get(2).startTick());
        // последний кусок тянется до конца слоя, а не до своего последнего шага
        assertEquals(300, retimed.get(2).endTick());
    }

    @Test
    void кускиИдутВстыкБезПробелов() {
        MeasuredTiming timing = evenly(0, 0, 12);
        List<LayerShots.Unit> units = List.of(unit(0, 5), unit(5, 4), unit(9, 3));

        List<LayerShots.Unit> retimed = timing.retime(0, units, 0, 12, 0, 200);

        for (int i = 0; i + 1 < retimed.size(); i++) {
            assertEquals(retimed.get(i).endTick(), retimed.get(i + 1).startTick(),
                    "между кусками " + i + " и " + (i + 1) + " образовался пробел");
        }
    }

    @Test
    void номераШаговСмещаютсяНаНачалоСлоя() {
        // слой не первый: его шаги в общем порядке начинаются с 40
        MeasuredTiming timing = evenly(2, 500, 6);
        List<LayerShots.Unit> units = List.of(unit(40, 2), unit(42, 4));

        List<LayerShots.Unit> retimed = timing.retime(2, units, 40, 6, 500, 900);

        assertEquals(510, retimed.get(0).startTick());
        assertEquals(514, retimed.get(1).startTick());
    }

    @Test
    void другоеЧислоШаговОтвергается() {
        MeasuredTiming timing = evenly(0, 100, 10);
        assertNotNull(timing.mismatch(0, 12, 100));

        List<LayerShots.Unit> units = List.of(unit(0, 12));
        assertSame(units, timing.retime(0, units, 0, 12, 100, 300));
    }

    @Test
    void расхождениеСМеткойОтвергается() {
        MeasuredTiming timing = evenly(0, 100, 10);
        // зазор в тик между потоками — это нормально
        assertNull(timing.mismatch(0, 10, 101));
        // а вот запись из другого дубля начинается совсем не там
        assertNotNull(timing.mismatch(0, 10, 640));

        List<LayerShots.Unit> units = List.of(unit(0, 10));
        assertSame(units, timing.retime(0, units, 0, 10, 640, 300));
    }

    @Test
    void слойБезЗамеровОстаётсяКакБыл() {
        MeasuredTiming timing = new MeasuredTiming();
        List<LayerShots.Unit> units = List.of(unit(0, 3));
        assertSame(units, timing.retime(0, units, 0, 3, 0, 100));
    }

    @Test
    void заминкаВКладкеНеСдвигаетСледующийКусок() {
        // Ради этого всё и затевалось: первый кусок кладут с заминкой, второй — обычно.
        // Пропорция размазала бы задержку по всему слою и увела бы границу; замер — нет.
        MeasuredTiming timing = new MeasuredTiming();
        timing.layerStarted(0, 0);
        int[] ticks = {10, 12, 14, 16, 90, 92, 94, 96};
        for (int tick : ticks) {
            timing.stepPlaced(0, tick);
        }
        List<LayerShots.Unit> units = List.of(unit(0, 4), unit(4, 4));

        List<LayerShots.Unit> retimed = timing.retime(0, units, 0, 8, 0, 200);

        assertEquals(10, retimed.get(0).startTick());
        // граница ровно там, где работа перешла на другую сторону, а не в середине слоя
        assertEquals(90, retimed.get(1).startTick());
    }
}

package com.tutorialschematic.order;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureDistanceTest {

    private static double distanceAt(List<Pos> blocks, double[] distances, Pos target) {
        return distances[blocks.indexOf(target)];
    }

    @Test
    void прямаяЛинияСчитаетсяПошагово() {
        List<Pos> line = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            line.add(new Pos(x, 0, 0));
        }
        double[] d = StructureDistance.compute(line, List.of(new Pos(0, 0, 0)));

        for (int x = 0; x < 5; x++) {
            assertEquals(x, distanceAt(line, d, new Pos(x, 0, 0)));
        }
    }

    @Test
    void ступенькаПроходитсяПоДиагонали() {
        // кромка скатной крыши: блоки касаются только ребром. По граням обход бы оборвался
        List<Pos> stairs = List.of(
                new Pos(0, 0, 0), new Pos(1, 1, 0), new Pos(2, 2, 0), new Pos(3, 3, 0));
        double[] d = StructureDistance.compute(stairs, List.of(new Pos(0, 0, 0)));

        assertEquals(1, distanceAt(stairs, d, new Pos(1, 1, 0)));
        assertEquals(3, distanceAt(stairs, d, new Pos(3, 3, 0)));
    }

    @Test
    void обходДырыИдётВокругАНеНасквозь() {
        // кольцо 5x5 без середины: от одного угла до соседнего по кольцу далеко,
        // хотя по прямой они рядом
        List<Pos> ring = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                if (edge) {
                    ring.add(new Pos(x, 0, z));
                }
            }
        }
        double[] d = StructureDistance.compute(ring, List.of(new Pos(0, 0, 0)));

        // Ровно тот случай, ради которого всё и делается. По прямой от угла до угла
        // четыре шага наискось, но середина кольца пустая — идти приходится краем,
        // и получается семь: (0,0)→(1,0)→(2,0)→(3,0)→(4,1)→(4,2)→(4,3)→(4,4).
        assertEquals(7, distanceAt(ring, d, new Pos(4, 0, 4)));
        assertTrue(distanceAt(ring, d, new Pos(4, 0, 4)) > 4, "по прямой было бы четыре");

        // середина правой стороны: пять шагов краем вместо четырёх по прямой
        assertEquals(5, distanceAt(ring, d, new Pos(4, 0, 2)));
    }

    @Test
    void несвязныйКусокУходитВКонец() {
        List<Pos> blocks = List.of(
                new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(2, 0, 0),
                new Pos(20, 0, 0));
        double[] d = StructureDistance.compute(blocks, List.of(new Pos(0, 0, 0)));

        double far = distanceAt(blocks, d, new Pos(20, 0, 0));
        assertEquals(3, far, "самый дальний достижимый = 2, значит недостижимому достаётся 3");
        assertTrue(far > distanceAt(blocks, d, new Pos(2, 0, 0)));
    }

    @Test
    void несколькоЗатравокИдутНавстречу() {
        List<Pos> line = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            line.add(new Pos(x, 0, 0));
        }
        double[] d = StructureDistance.compute(line, List.of(new Pos(0, 0, 0), new Pos(6, 0, 0)));

        assertEquals(0, distanceAt(line, d, new Pos(0, 0, 0)));
        assertEquals(0, distanceAt(line, d, new Pos(6, 0, 0)));
        // середина — самая дальняя от обоих концов
        assertEquals(3, distanceAt(line, d, new Pos(3, 0, 0)));
    }

    @Test
    void безЗатравокНачинаетСнизу() {
        List<Pos> tower = List.of(
                new Pos(0, 0, 0), new Pos(0, 1, 0), new Pos(0, 2, 0));
        double[] d = StructureDistance.compute(tower, List.of());

        assertEquals(0, distanceAt(tower, d, new Pos(0, 0, 0)));
        assertEquals(2, distanceAt(tower, d, new Pos(0, 2, 0)));
    }

    @Test
    void затравкаВнеСлояИгнорируется() {
        List<Pos> line = List.of(new Pos(0, 0, 0), new Pos(1, 0, 0));
        // выбранного блока в слое нет — откатываемся на автоматический выбор снизу
        double[] d = StructureDistance.compute(line, List.of(new Pos(99, 99, 99)));

        assertEquals(0, distanceAt(line, d, new Pos(0, 0, 0)));
        assertEquals(1, distanceAt(line, d, new Pos(1, 0, 0)));
    }

    @Test
    void пустойСлойНеПадает() {
        assertEquals(0, StructureDistance.compute(List.of(), List.of()).length);
    }
}

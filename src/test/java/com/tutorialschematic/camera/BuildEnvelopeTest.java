package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildEnvelopeTest {

    /** Коробка блоков 0..9 по X и Z, 0..4 по Y — как небольшой дом. */
    private static BuildEnvelope box() {
        List<Pos> blocks = new ArrayList<>();
        for (int x = 0; x <= 9; x++) {
            for (int z = 0; z <= 9; z++) {
                blocks.add(new Pos(x, 0, z));
                blocks.add(new Pos(x, 4, z));
            }
        }
        return BuildEnvelope.around(blocks);
    }

    @Test
    void точкаВнутриИСнаружиРазличаются() {
        BuildEnvelope envelope = box();
        assertTrue(envelope.contains(new double[]{5, 2, 5}), "центр дома внутри");
        assertFalse(envelope.contains(new double[]{5, 2, 30}), "точка во дворе снаружи");
    }

    @Test
    void лучИзЦентраВыходитНаГраницеГабарита() {
        BuildEnvelope envelope = box();
        // блоки 0..9 => внешняя грань 10, плюс запас 1.5 => 11.5; из z=5 это 6.5
        double exit = envelope.exitDistance(new double[]{5, 2.5, 5}, 0, 0);
        assertEquals(6.51, exit, 1.0e-9);
    }

    @Test
    void лучВверхВыходитЧерезКрышу() {
        BuildEnvelope envelope = box();
        // блоки до y=4 => внешняя грань 5, плюс запас 1.5 => 6.5; из y=2.5 это 4
        double exit = envelope.exitDistance(new double[]{5, 2.5, 5}, 0, 90);
        assertEquals(4.01, exit, 1.0e-9);
    }

    @Test
    void снаружиОтВернувшегосяЛучаПорогНулевой() {
        BuildEnvelope envelope = box();
        // камера уже во дворе и уходит дальше от дома — ограничивать нечего
        assertEquals(0.0, envelope.exitDistance(new double[]{5, 2.5, 30}, 0, 0), 1.0e-9);
    }

    @Test
    void лучСквозьДомОграничиваетсяЕгоДальнейГранью() {
        BuildEnvelope envelope = box();
        // прицел за домом, луч проходит дом насквозь: стоять можно только за его дальней гранью
        double exit = envelope.exitDistance(new double[]{5, 2.5, -20}, 0, 0);
        assertEquals(31.51, exit, 1.0e-9);
    }
}

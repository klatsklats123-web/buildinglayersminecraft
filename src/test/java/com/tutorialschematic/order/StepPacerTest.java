package com.tutorialschematic.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepPacerTest {

    /** Сколько шагов набежит за указанное число тиков. */
    private static int stepsOver(int ticks, int ticksPerStep, double speed) {
        StepPacer pacer = new StepPacer();
        int total = 0;
        for (int i = 0; i < ticks; i++) {
            total += pacer.stepsThisTick(ticksPerStep, speed);
        }
        return total;
    }

    @Test
    void периодРавенЗаданномуЧислуТиков() {
        // ровно то, что обещают BuildLayer.estimatedSeconds и OrderConfig.blocksPerSecond:
        // шаг раз в ticksPerStep тиков, без лишнего тика сверху
        assertEquals(20, stepsOver(20, 1, 1.0));
        assertEquals(10, stepsOver(20, 2, 1.0));
        assertEquals(5, stepsOver(20, 4, 1.0));
    }

    @Test
    void нольТиковОзначаетКаждыйТик() {
        assertEquals(20, stepsOver(20, 0, 1.0));
        assertEquals(20, stepsOver(20, -3, 1.0));
    }

    @Test
    void скоростьМасштабируетТемпЛинейно() {
        // это и было сломано: округление до целых тиков склеивало x2 и x4 в один темп
        assertEquals(10, stepsOver(20, 2, 1.0));
        assertEquals(20, stepsOver(20, 2, 2.0));
        assertEquals(40, stepsOver(20, 2, 4.0));
        assertEquals(80, stepsOver(20, 2, 8.0));
    }

    @Test
    void дробнаяСкоростьНеТеряетсяНаОкруглении() {
        // x1.5 обязан отличаться и от x1, и от x2
        assertEquals(15, stepsOver(20, 2, 1.5));
        assertEquals(13, stepsOver(20, 3, 2.0));
    }

    @Test
    void замедлениеРастягиваетПостройку() {
        assertEquals(5, stepsOver(20, 2, 0.5));
        assertEquals(1, stepsOver(20, 2, 0.1));
    }

    @Test
    void остатокПереноситсяМеждуТиками() {
        StepPacer pacer = new StepPacer();
        // 0.5 шага за тик: ноль, потом один, и так по кругу
        assertEquals(0, pacer.stepsThisTick(2, 1.0));
        assertEquals(1, pacer.stepsThisTick(2, 1.0));
        assertEquals(0, pacer.stepsThisTick(2, 1.0));
        assertEquals(1, pacer.stepsThisTick(2, 1.0));
    }

    @Test
    void сбросНачинаетОтсчётЗаново() {
        StepPacer pacer = new StepPacer();
        pacer.stepsThisTick(2, 1.0);
        pacer.reset();
        // после сброса накопленная половина забыта, значит первый тик снова пустой
        assertEquals(0, pacer.stepsThisTick(2, 1.0));
    }

    @Test
    void темпСовпадаетСОценкойДлительности() {
        // 300 блоков по 2 тика на шаг — это 30 секунд, то есть 600 тиков
        int ticksPerStep = 2;
        int steps = 300;
        int ticks = steps * ticksPerStep;
        assertEquals(steps, stepsOver(ticks, ticksPerStep, 1.0));

        double estimatedSeconds = steps * ticksPerStep / 20.0;
        assertEquals(30.0, estimatedSeconds, 1e-9);
    }

    @Test
    void оченьБольшоеУскорениеДаётНесколькоШаговЗаТик() {
        StepPacer pacer = new StepPacer();
        assertTrue(pacer.stepsThisTick(1, 20.0) >= 20);
    }
}

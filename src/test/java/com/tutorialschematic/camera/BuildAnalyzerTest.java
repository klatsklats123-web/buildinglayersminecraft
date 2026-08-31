package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildAnalyzerTest {

    /** Столбик: каждый шаг — один блок, XZ не меняется, растёт только Y. */
    private static List<List<Pos>> column(int height) {
        List<List<Pos>> steps = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            steps.add(List.of(new Pos(0, y, 0)));
        }
        return steps;
    }

    /** Плоский пол шириной width на depth, идёт рядами вдоль X. */
    private static List<List<Pos>> floor(int width, int depth) {
        List<List<Pos>> steps = new ArrayList<>();
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                steps.add(List.of(new Pos(x, 0, z)));
            }
        }
        return steps;
    }

    /** Стена едет вдоль X, потом поворачивает и едет вдоль Z — угол дома. */
    private static List<List<Pos>> turningWall(int legLength) {
        List<List<Pos>> steps = new ArrayList<>();
        for (int x = 0; x < legLength; x++) {
            steps.add(List.of(new Pos(x, 0, 0), new Pos(x, 1, 0), new Pos(x, 2, 0)));
        }
        for (int z = 1; z <= legLength; z++) {
            steps.add(List.of(new Pos(legLength, 0, z), new Pos(legLength, 1, z), new Pos(legLength, 2, z)));
        }
        return steps;
    }

    private static int[] evenTicks(int stepCount, int startTick, int endTick) {
        int[] ticks = new int[stepCount + 1];
        for (int i = 0; i <= stepCount; i++) {
            ticks[i] = startTick + (int) ((long) i * (endTick - startTick) / stepCount);
        }
        return ticks;
    }

    @Test
    void столбикОстаётсяОднимУчасткомВертикальнойФормы() {
        List<List<Pos>> steps = column(20);
        List<BuildAnalyzer.WorkSegment> segments = BuildAnalyzer.analyze(steps, evenTicks(steps.size(), 0, 400));

        assertEquals(1, segments.size(), "рост столба не должен резаться на куски");
        assertEquals(BuildAnalyzer.Shape.VERTICAL, segments.get(0).shape());
        assertEquals(20, segments.get(0).blocks().size());
    }

    @Test
    void полОстаётсяОднимУчасткомПлоскойФормы() {
        List<List<Pos>> steps = floor(10, 10);
        List<BuildAnalyzer.WorkSegment> segments = BuildAnalyzer.analyze(steps, evenTicks(steps.size(), 0, 2000));

        // Ряды идут туда-сюда по X — зигзаг не должен читаться как поворот фронта,
        // потому что направление имеет смысл только для формы LINEAR.
        assertEquals(1, segments.size(), "зигзаг рядов пола не должен резать участок");
        assertEquals(BuildAnalyzer.Shape.FLAT, segments.get(0).shape());
    }

    @Test
    void стенаРежетсяНаУглу() {
        List<List<Pos>> steps = turningWall(20);
        List<BuildAnalyzer.WorkSegment> segments = BuildAnalyzer.analyze(steps, evenTicks(steps.size(), 0, 800));

        assertTrue(segments.size() >= 2, "поворот на девяносто градусов обязан дать новый участок");
        for (BuildAnalyzer.WorkSegment segment : segments) {
            assertEquals(BuildAnalyzer.Shape.LINEAR, segment.shape(), "прямая стена — линейная форма");
        }
        double firstDirection = segments.get(0).direction();
        double lastDirection = segments.get(segments.size() - 1).direction();
        double turn = Math.abs(ShotPlanner.shortestTurn(lastDirection - firstDirection));
        assertTrue(turn > 45, "направление первого и последнего участка должно ощутимо разойтись: " + turn);
    }

    @Test
    void паузаВоВремениНичегоНеРежет() {
        // Геометрия не знает о паузах — соседние по геометрии шаги остаются в одном участке,
        // на каком бы расстоянии по тикам они ни оказались.
        List<List<Pos>> steps = column(10);
        int[] ticks = new int[steps.size() + 1];
        ticks[0] = 0;
        for (int i = 1; i <= steps.size(); i++) {
            // огромный, неравномерный разрыв между некоторыми шагами — имитация паузы
            ticks[i] = ticks[i - 1] + (i == 5 ? 5000 : 20);
        }
        List<BuildAnalyzer.WorkSegment> segments = BuildAnalyzer.analyze(steps, ticks);

        assertEquals(1, segments.size(), "пауза по времени не должна резать участок");
    }

    @Test
    void границыТиковСохраняютПорядок() {
        List<List<Pos>> steps = turningWall(20);
        List<BuildAnalyzer.WorkSegment> segments = BuildAnalyzer.analyze(steps, evenTicks(steps.size(), 100, 900));

        for (int i = 1; i < segments.size(); i++) {
            assertTrue(segments.get(i).startTick() >= segments.get(i - 1).endTick() - 1,
                    "участки должны идти по возрастанию тиков");
            assertTrue(segments.get(i).startStep() >= segments.get(i - 1).endStep(),
                    "и по возрастанию индекса шага");
        }
    }
}

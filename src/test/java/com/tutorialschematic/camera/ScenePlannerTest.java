package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenePlannerTest {

    /** Линейный участок вдоль X на заданной глубине z, с явным направлением фронта. */
    private static BuildAnalyzer.WorkSegment linearSegment(int fromX, int toX, int z, double direction,
                                                            int startStep, int startTick, int endTick) {
        List<Pos> blocks = new ArrayList<>();
        List<List<Pos>> steps = new ArrayList<>();
        for (int x = fromX; x < toX; x++) {
            List<Pos> step = List.of(new Pos(x, 0, z), new Pos(x, 1, z), new Pos(x, 2, z));
            blocks.addAll(step);
            steps.add(step);
        }
        double[] center = ShotPlanner.centerOf(blocks);
        double radius = ShotPlanner.radiusOf(blocks, center);
        return new BuildAnalyzer.WorkSegment(blocks, steps, center, radius, direction,
                BuildAnalyzer.Shape.LINEAR, startStep, startStep + steps.size(), startTick, endTick);
    }

    private static BuildAnalyzer.WorkSegment flatSegment(int width, int depth, int startStep,
                                                          int startTick, int endTick) {
        List<Pos> blocks = new ArrayList<>();
        List<List<Pos>> steps = new ArrayList<>();
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                List<Pos> step = List.of(new Pos(x, 0, z));
                blocks.add(new Pos(x, 0, z));
                steps.add(step);
            }
        }
        double[] center = ShotPlanner.centerOf(blocks);
        double radius = ShotPlanner.radiusOf(blocks, center);
        return new BuildAnalyzer.WorkSegment(blocks, steps, center, radius, Double.NaN,
                BuildAnalyzer.Shape.FLAT, startStep, startStep + steps.size(), startTick, endTick);
    }

    @Test
    void широкийДопускСливаетНебольшойПоворотВОднуСцену() {
        BuildAnalyzer.WorkSegment first = linearSegment(0, 10, 0, 0, 0, 0, 100);
        BuildAnalyzer.WorkSegment second = linearSegment(0, 10, 0, 40, 10, 100, 200);

        ShotPolicy wide = new ShotPolicy(65, 100, 4);
        List<ScenePlanner.Scene> scenes = ScenePlanner.buildScenes(List.of(first, second), wide);

        assertEquals(1, scenes.size(), "поворот в сорок градусов укладывается в широкий допуск");
    }

    @Test
    void узкийДопускРежетТотЖеПоворот() {
        BuildAnalyzer.WorkSegment first = linearSegment(0, 10, 0, 0, 0, 0, 100);
        BuildAnalyzer.WorkSegment second = linearSegment(0, 10, 0, 40, 10, 100, 200);

        ShotPolicy tight = new ShotPolicy(20, 100, 4);
        List<ScenePlanner.Scene> scenes = ScenePlanner.buildScenes(List.of(first, second), tight);

        assertEquals(2, scenes.size(), "поворот в сорок градусов превышает узкий допуск в двадцать");
    }

    @Test
    void маленькаяСценаНеРежетсяДажеПриПревышенииДопуска() {
        BuildAnalyzer.WorkSegment first = linearSegment(0, 2, 0, 0, 0, 0, 20);
        BuildAnalyzer.WorkSegment second = linearSegment(0, 2, 0, 170, 2, 20, 40);

        ShotPolicy tight = new ShotPolicy(20, 100, 20);
        List<ScenePlanner.Scene> scenes = ScenePlanner.buildScenes(List.of(first, second), tight);

        assertEquals(1, scenes.size(), "накопленного меньше минимума — резать ещё рано");
    }

    @Test
    void огромныйПлоскийУчастокРежетсяПоРадиусуДляУзкойДорожки() {
        BuildAnalyzer.WorkSegment huge = flatSegment(30, 30, 0, 0, 3000);

        ShotPolicy near = new ShotPolicy(28, 12, 6);
        List<ScenePlanner.Scene> scenes = ScenePlanner.buildScenes(List.of(huge), near);

        assertTrue(scenes.size() > 1, "тридцать на тридцать блоков не влезает в радиус двенадцать одним куском");
        for (ScenePlanner.Scene scene : scenes) {
            assertTrue(scene.radius() <= 12.0 + 1.0e-6 || scene.blocks().size() <= near.minSceneBlocks(),
                    "кусок либо укладывается в радиус, либо это минимально допустимый остаток");
        }
    }

    @Test
    void широкаяДорожкаНеРежетТотЖеУчасток() {
        BuildAnalyzer.WorkSegment huge = flatSegment(30, 30, 0, 0, 3000);

        ShotPolicy far = new ShotPolicy(70, 40, 8);
        List<ScenePlanner.Scene> scenes = ScenePlanner.buildScenes(List.of(huge), far);

        assertEquals(1, scenes.size(), "радиус тридцать на тридцать укладывается в допуск дальнего плана");
    }

    @Test
    void кускиПослеРезкиИдутПодрядБезПропусковИНаложений() {
        BuildAnalyzer.WorkSegment huge = flatSegment(30, 30, 0, 0, 3000);
        ShotPolicy near = new ShotPolicy(28, 12, 6);
        List<ScenePlanner.Scene> scenes = ScenePlanner.buildScenes(List.of(huge), near);

        int totalBlocks = 0;
        for (ScenePlanner.Scene scene : scenes) {
            totalBlocks += scene.blocks().size();
        }
        assertEquals(900, totalBlocks, "ни один блок не потерялся и не задвоился при нарезке");

        for (int i = 1; i < scenes.size(); i++) {
            assertTrue(scenes.get(i).firstGlobalStep() > scenes.get(i - 1).firstGlobalStep(),
                    "куски должны идти в порядке появления");
        }
    }

    /** Сцена из готовых блоков и шагов по одному блоку. */
    private static ScenePlanner.Scene sceneOf(List<Pos> blocks, BuildAnalyzer.Shape shape) {
        List<List<Pos>> steps = new ArrayList<>();
        for (Pos pos : blocks) {
            steps.add(List.of(pos));
        }
        double[] center = ShotPlanner.centerOf(blocks);
        double radius = ShotPlanner.radiusOf(blocks, center);
        return new ScenePlanner.Scene(blocks, steps, center, radius, shape, Double.NaN, 0, 100, 0);
    }

    @Test
    void интерьерЗаГотовымиСтенамиНеВиденСнаружи() {
        // Комната 12x12, стены в пять блоков и крыша — уже стоят. Внутри кладётся мебель.
        Set<Pos> walls = new HashSet<>();
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                if (x == 0 || x == 11 || z == 0 || z == 11) {
                    for (int y = 0; y < 5; y++) {
                        walls.add(new Pos(x, y, z));
                    }
                }
                walls.add(new Pos(x, 5, z));
            }
        }
        List<Pos> furniture = new ArrayList<>();
        for (int x = 4; x <= 7; x++) {
            for (int z = 4; z <= 7; z++) {
                furniture.add(new Pos(x, 1, z));
            }
        }
        List<Pos> everything = new ArrayList<>(walls);
        everything.addAll(furniture);
        BuildEnvelope envelope = BuildEnvelope.around(everything);

        ScenePlanner.Scene interior = sceneOf(furniture, BuildAnalyzer.Shape.FLAT);
        List<Set<Pos>> builtBefore = new ArrayList<>();
        for (int i = 0; i < interior.steps().size(); i++) {
            builtBefore.add(walls);
        }

        assertFalse(ScenePlanner.visibleFromOutside(interior, builtBefore,
                        ShotStyle.MID_HOLD, 70, envelope),
                "мебель за глухими стенами снаружи не видна — это интерьер");

        // А открытая стена при пустом дворе снаружи видна отлично.
        List<Pos> wall = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 6; y++) {
                wall.add(new Pos(x, y, 0));
            }
        }
        ScenePlanner.Scene open = sceneOf(wall, BuildAnalyzer.Shape.LINEAR);
        List<Set<Pos>> nothingBuilt = new ArrayList<>();
        for (int i = 0; i < open.steps().size(); i++) {
            nothingBuilt.add(Set.of());
        }
        assertTrue(ScenePlanner.visibleFromOutside(open, nothingBuilt,
                        ShotStyle.MID_HOLD, 70, BuildEnvelope.around(wall)),
                "открытая стена снаружи видна");
    }

    @Test
    void разнаяФормаВсегдаРежетНезависимоОтДопуска() {
        BuildAnalyzer.WorkSegment flat = flatSegment(10, 10, 0, 0, 500);
        BuildAnalyzer.WorkSegment vertical = new BuildAnalyzer.WorkSegment(
                List.of(new Pos(0, 1, 0), new Pos(0, 2, 0), new Pos(0, 3, 0), new Pos(0, 4, 0),
                        new Pos(0, 5, 0), new Pos(0, 6, 0)),
                List.of(List.of(new Pos(0, 1, 0)), List.of(new Pos(0, 2, 0)), List.of(new Pos(0, 3, 0)),
                        List.of(new Pos(0, 4, 0)), List.of(new Pos(0, 5, 0)), List.of(new Pos(0, 6, 0))),
                new double[]{0.5, 3.5, 0.5}, 3.0, Double.NaN, BuildAnalyzer.Shape.VERTICAL,
                100, 106, 500, 600);

        ShotPolicy generous = new ShotPolicy(180, Double.MAX_VALUE, 1);
        List<ScenePlanner.Scene> scenes = ScenePlanner.buildScenes(List.of(flat, vertical), generous);

        assertEquals(2, scenes.size(), "пол и столб не должны слиться, даже при бесконечно щедрой политике");
    }
}

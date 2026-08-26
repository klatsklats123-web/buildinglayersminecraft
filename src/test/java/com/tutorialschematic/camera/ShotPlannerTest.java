package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShotPlannerTest {

    /** Стена в плоскости XY на заданной глубине z. */
    private static List<Pos> wall(int z, int width, int height) {
        List<Pos> blocks = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                blocks.add(new Pos(x, y, z));
            }
        }
        return blocks;
    }

    @Test
    void камераОбходитЗаслонившуюСтену() {
        // снимаем стену на z=0, а на z=-6 уже стоит глухая стена побольше.
        // Значит камеру надо ставить с той стороны, где её нет, то есть z положительный.
        List<Pos> target = wall(0, 9, 6);
        Set<Pos> occluders = new HashSet<>(wall(-6, 21, 14));

        CameraShot shot = ShotPlanner.plan(target, occluders, ShotStyle.MID_HOLD, 70, 0);

        assertTrue(shot.z() > 0, "камера ушла на свободную сторону, а не за глухую стену");
    }

    @Test
    void безЗаслоновРакурсВсёРавноВалиден() {
        List<Pos> target = wall(0, 9, 6);
        CameraShot shot = ShotPlanner.plan(target, Set.of(), ShotStyle.MID_HOLD, 70, 0);

        double[] center = ShotPlanner.centerOf(target);
        double[] from = {shot.x(), shot.y(), shot.z()};
        double[] dir = CameraFraming.direction(shot.yaw(), shot.pitch());

        double dx = center[0] - from[0], dy = center[1] - from[1], dz = center[2] - from[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        assertEquals(dx / length, dir[0], 1.0e-5, "камера смотрит в центр слоя");
        assertEquals(dz / length, dir[2], 1.0e-5);
    }

    @Test
    void камераНеУходитВЗенит() {
        // геометрически сверху видно всё, но снимать так нельзя — доктрина держит рамки
        List<Pos> target = wall(0, 9, 6);
        Set<Pos> occluders = new HashSet<>();

        for (ShotStyle style : ShotStyle.values()) {
            CameraShot shot = ShotPlanner.plan(target, occluders, style, 70, 0);
            double[] center = ShotPlanner.centerOf(target);
            double dy = shot.y() - center[1];
            double horizontal = Math.hypot(shot.x() - center[0], shot.z() - center[2]);
            double elevation = Math.toDegrees(Math.atan2(dy, horizontal));

            assertTrue(elevation <= style.maxElevation() + 0.001,
                    style.displayName() + ": подъём " + elevation + " вышел за диапазон");
            assertTrue(elevation >= style.minElevation() - 0.001,
                    style.displayName() + ": подъём " + elevation + " ниже диапазона");
        }
    }

    @Test
    void ближнийРакурсБлижеДальнего() {
        List<Pos> target = wall(0, 15, 10);
        double[] center = ShotPlanner.centerOf(target);

        double far = distance(ShotPlanner.plan(target, Set.of(), ShotStyle.FAR_HOLD, 70, 0), center);
        double mid = distance(ShotPlanner.plan(target, Set.of(), ShotStyle.MID_HOLD, 70, 0), center);
        double near = distance(ShotPlanner.plan(target, Set.of(), ShotStyle.NEAR_HOLD, 70, 0), center);

        assertTrue(near < mid, "ближний ближе среднего");
        assertTrue(mid < far, "средний ближе дальнего");
    }

    @Test
    void пролётДаётДваКадраСРазныхТочек() {
        List<Pos> target = wall(0, 9, 6);
        List<CameraShot> flight = ShotPlanner.planFlight(
                target, Set.of(), ShotStyle.MID_FLY, 70, 100, 260, 40);

        assertEquals(2, flight.size());
        assertEquals(100, flight.get(0).tick());
        assertEquals(260, flight.get(1).tick());

        double moved = Math.hypot(flight.get(0).x() - flight.get(1).x(),
                flight.get(0).z() - flight.get(1).z());
        assertTrue(moved > 1, "камера действительно проехала");

        // но осталась на том же расстоянии от объекта — это дуга, а не наезд
        double[] center = ShotPlanner.centerOf(target);
        assertEquals(distance(flight.get(0), center), distance(flight.get(1), center), 1.0e-6);
    }

    @Test
    void лучВидитЦельНапрямуюИНеВидитСквозьБлок() {
        Pos target = new Pos(0, 0, 0);
        double[] camera = {0.5, 0.5, 8.5};

        assertTrue(Occlusion.isVisible(camera, target, Set.of()));
        assertTrue(!Occlusion.isVisible(camera, target, Set.of(new Pos(0, 0, 4))),
                "блок ровно на пути должен заслонять");
    }

    private static double distance(CameraShot shot, double[] center) {
        return Math.sqrt(Math.pow(shot.x() - center[0], 2)
                + Math.pow(shot.y() - center[1], 2)
                + Math.pow(shot.z() - center[2], 2));
    }
}

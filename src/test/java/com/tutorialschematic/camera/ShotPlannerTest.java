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

    private static ShotPlanner.Placement plan(List<Pos> targets, Set<Pos> occluders, ShotStyle style) {
        return ShotPlanner.plan(targets, occluders, style, 70, 0, Double.NaN);
    }

    @Test
    void камераОбходитЗаслонившуюСтену() {
        // снимаем стену на z=0, а на z=-6 уже стоит глухая стена побольше:
        // камера обязана уйти на свободную сторону
        List<Pos> target = wall(0, 9, 6);
        Set<Pos> occluders = new HashSet<>(wall(-6, 21, 14));

        ShotPlanner.Placement placement = plan(target, occluders, ShotStyle.MID_HOLD);
        assertTrue(placement.shot().z() > 0, "камера ушла туда, откуда видно");
    }

    @Test
    void приРавнойВидимостиВыбираетсяТриЧетверти() {
        // Главное исправление: раньше побеждал первый кандидат перебора и камера всегда
        // вставала в лоб. Теперь при равной видимости выигрывает диагональ.
        List<Pos> target = wall(0, 9, 6);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.MID_HOLD);

        double offset = Math.abs(((placement.azimuth() % 90) + 90) % 90 - 45);
        assertTrue(offset < 20,
                "азимут " + placement.azimuth() + " должен быть ближе к диагонали, чем к оси");
    }

    @Test
    void высотаБерётсяИзСерединыДиапазонаАНеСКрая() {
        List<Pos> target = wall(0, 9, 6);
        for (ShotStyle style : ShotStyle.values()) {
            ShotPlanner.Placement placement = plan(target, Set.of(), style);
            double middle = (style.minElevation() + style.maxElevation()) / 2;

            assertEquals(middle, placement.elevation(), 1.0e-6,
                    style.displayName() + ": при пустом дворе высота должна быть посередине");
        }
    }

    @Test
    void камераНеУходитВЗенит() {
        List<Pos> target = wall(0, 9, 6);
        for (ShotStyle style : ShotStyle.values()) {
            ShotPlanner.Placement placement = plan(target, Set.of(), style);
            assertTrue(placement.elevation() <= style.maxElevation() + 1.0e-6,
                    style.displayName() + ": подъём вышел за диапазон");
            assertTrue(placement.elevation() >= style.minElevation() - 1.0e-6,
                    style.displayName() + ": подъём ниже диапазона");
        }
    }

    @Test
    void соседниеПланыРазворачиваютсяНеМенееЧемНаТридцатьГрадусов() {
        // иначе склейка читается рывком, а не сменой плана
        List<Pos> target = wall(0, 9, 6);
        double previous = plan(target, Set.of(), ShotStyle.MID_HOLD).azimuth();

        ShotPlanner.Placement next = ShotPlanner.plan(target, Set.of(), ShotStyle.MID_HOLD, 70, 100, previous);
        double turn = Math.abs(ShotPlanner.shortestTurn(next.azimuth() - previous));

        assertTrue(turn >= 30, "разворот всего " + turn + " градусов — это рывок");
        assertTrue(turn <= 150, "разворот " + turn + " градусов — перескок через ось");
    }

    @Test
    void ближнийРакурсБлижеДальнего() {
        List<Pos> target = wall(0, 15, 10);
        double far = plan(target, Set.of(), ShotStyle.FAR_HOLD).distance();
        double mid = plan(target, Set.of(), ShotStyle.MID_HOLD).distance();
        double near = plan(target, Set.of(), ShotStyle.NEAR_HOLD).distance();

        assertTrue(near < mid, "ближний ближе среднего");
        assertTrue(mid < far, "средний ближе дальнего");
    }

    @Test
    void наездПриближаетАОтъездОтдаляет() {
        List<Pos> target = wall(0, 9, 6);

        ShotPlanner.Placement in = plan(target, Set.of(), ShotStyle.DOLLY_IN);
        List<CameraShot> inShots = ShotPlanner.movementShots(target, in, ShotStyle.DOLLY_IN, 400);
        assertEquals(2, inShots.size());
        assertTrue(distance(inShots.get(1), target) < distance(inShots.get(0), target), "наезд приближает");

        ShotPlanner.Placement out = plan(target, Set.of(), ShotStyle.DOLLY_OUT);
        List<CameraShot> outShots = ShotPlanner.movementShots(target, out, ShotStyle.DOLLY_OUT, 400);
        assertTrue(distance(outShots.get(1), target) > distance(outShots.get(0), target), "отъезд отдаляет");
    }

    @Test
    void неподвижнаяДоктринаДаётОдинКадр() {
        List<Pos> target = wall(0, 9, 6);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.FAR_HOLD);

        assertEquals(1, ShotPlanner.movementShots(target, placement, ShotStyle.FAR_HOLD, 400).size());
    }

    @Test
    void дугаУводитКамеруВБокНоНеМеняетДистанцию() {
        List<Pos> target = wall(0, 9, 6);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.MID_ARC);
        List<CameraShot> shots = ShotPlanner.movementShots(target, placement, ShotStyle.MID_ARC, 400);

        assertEquals(2, shots.size());
        assertEquals(distance(shots.get(0), target), distance(shots.get(1), target), 1.0e-6);

        double moved = Math.hypot(shots.get(0).x() - shots.get(1).x(), shots.get(0).z() - shots.get(1).z());
        assertTrue(moved > 1, "камера действительно проехала");
    }

    @Test
    void кадрыДвиженияНеСовпадаютПоТику() {
        // совпадение затёрло бы соседний кадр: они лежат в словаре по номеру тика
        List<Pos> target = wall(0, 9, 6);
        ShotPlanner.Placement placement = ShotPlanner.plan(target, Set.of(), ShotStyle.ORBIT, 70, 100, Double.NaN);
        List<CameraShot> shots = ShotPlanner.movementShots(target, placement, ShotStyle.ORBIT, 300);

        assertEquals(2, shots.size());
        assertTrue(shots.get(0).tick() < shots.get(1).tick());
    }

    @Test
    void целимсяВышеЦентраРадиПравилаТретей() {
        // постройка должна садиться в нижние две трети кадра, а не делить его пополам
        List<Pos> target = wall(0, 9, 20);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.MID_HOLD);
        double[] center = ShotPlanner.centerOf(target);

        double[] from = {placement.shot().x(), placement.shot().y(), placement.shot().z()};
        double[] dir = CameraFraming.direction(placement.shot().yaw(), placement.shot().pitch());

        // луч взгляда проходит выше геометрического центра
        double horizontal = Math.hypot(center[0] - from[0], center[2] - from[2]);
        double aimedY = from[1] + dir[1] / Math.hypot(dir[0], dir[2]) * horizontal;
        assertTrue(aimedY > center[1], "точка прицеливания выше центра слоя");
    }

    /** Горизонтальный лист — пол или фундамент. */
    private static List<Pos> floor(int y, int width, int depth) {
        List<Pos> blocks = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new Pos(x, y, z));
            }
        }
        return blocks;
    }

    @Test
    void плоскийСлойСнимаетсяВыше() {
        // Ровно возражение из практики: фундамент с малой высоты виден с торца, то есть
        // никак. Стена при этом подниматься не должна.
        double floorElevation = plan(floor(0, 15, 15), Set.of(), ShotStyle.MID_HOLD).elevation();
        double wallElevation = plan(wall(0, 15, 10), Set.of(), ShotStyle.MID_HOLD).elevation();

        assertTrue(floorElevation > wallElevation + 10,
                "над полом " + floorElevation + ", над стеной " + wallElevation);
    }

    @Test
    void плоскостьСчитаетсяПоФорме() {
        assertTrue(ShotPlanner.flatness(floor(0, 20, 20)) > 0.8, "пол плоский");
        assertEquals(0.0, ShotPlanner.flatness(wall(0, 10, 10)), 1.0e-9, "стена не плоская");
        assertEquals(0.0, ShotPlanner.flatness(wall(0, 6, 12)), 1.0e-9, "высокая стена тем более");
    }

    @Test
    void камераНеПоднимаетсяВышеПотолкаДажеНадСамымПлоским() {
        // иначе адаптация по форме утащила бы нас в зенит, от которого мы и уходили
        double elevation = plan(floor(0, 60, 60), Set.of(), ShotStyle.OVERVIEW_HIGH).elevation();
        assertTrue(elevation <= 62.0 + 1.0e-6, "подъём " + elevation + " — это уже вид сверху");
    }

    @Test
    void лучВидитЦельНапрямуюИНеВидитСквозьБлок() {
        Pos target = new Pos(0, 0, 0);
        double[] camera = {0.5, 0.5, 8.5};

        assertTrue(Occlusion.isVisible(camera, target, Set.of()));
        assertTrue(!Occlusion.isVisible(camera, target, Set.of(new Pos(0, 0, 4))),
                "блок ровно на пути должен заслонять");
    }

    private static double distance(CameraShot shot, List<Pos> target) {
        double[] center = ShotPlanner.centerOf(target);
        return Math.sqrt(Math.pow(shot.x() - center[0], 2)
                + Math.pow(shot.y() - center[1], 2)
                + Math.pow(shot.z() - center[2], 2));
    }
}

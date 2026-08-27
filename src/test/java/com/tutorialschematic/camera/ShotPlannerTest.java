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

        ShotPlanner.Placement placement = plan(target, occluders, ShotStyle.MID_FOLLOW);
        assertTrue(placement.shot().z() > 0, "камера ушла туда, откуда видно");
    }

    @Test
    void приРавнойВидимостиВыбираетсяТриЧетверти() {
        // Главное исправление: раньше побеждал первый кандидат перебора и камера всегда
        // вставала в лоб. Теперь при равной видимости выигрывает диагональ.
        List<Pos> target = wall(0, 9, 6);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.MID_FOLLOW);

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
        double previous = plan(target, Set.of(), ShotStyle.MID_FOLLOW).azimuth();

        ShotPlanner.Placement next = ShotPlanner.plan(target, Set.of(), ShotStyle.MID_FOLLOW, 70, 100, previous);
        double turn = Math.abs(ShotPlanner.shortestTurn(next.azimuth() - previous));

        assertTrue(turn >= 30, "разворот всего " + turn + " градусов — это рывок");
        assertTrue(turn <= 150, "разворот " + turn + " градусов — перескок через ось");
    }

    @Test
    void ближнийРакурсБлижеДальнего() {
        List<Pos> target = wall(0, 15, 10);
        double far = plan(target, Set.of(), ShotStyle.FAR_HOLD).distance();
        double mid = plan(target, Set.of(), ShotStyle.MID_FOLLOW).distance();
        double near = plan(target, Set.of(), ShotStyle.NEAR_FOLLOW).distance();

        assertTrue(near < mid, "ближний ближе среднего");
        assertTrue(mid < far, "средний ближе дальнего");
    }

    @Test
    void наездПриближаетАОтъездОтдаляет() {
        List<Pos> target = wall(0, 9, 6);

        ShotPlanner.Placement in = plan(target, Set.of(), ShotStyle.DOLLY_IN);
        List<CameraShot> inShots = ShotPlanner.followShots(target, in, ShotStyle.DOLLY_IN, List.of(), 400);
        assertEquals(2, inShots.size());
        assertTrue(distance(inShots.get(1), target) < distance(inShots.get(0), target), "наезд приближает");

        ShotPlanner.Placement out = plan(target, Set.of(), ShotStyle.DOLLY_OUT);
        List<CameraShot> outShots = ShotPlanner.followShots(target, out, ShotStyle.DOLLY_OUT, List.of(), 400);
        assertTrue(distance(outShots.get(1), target) > distance(outShots.get(0), target), "отъезд отдаляет");
    }

    @Test
    void неподвижнаяДоктринаДаётОдинКадр() {
        List<Pos> target = wall(0, 9, 6);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.FAR_HOLD);

        assertEquals(1, ShotPlanner.followShots(target, placement, ShotStyle.FAR_HOLD, List.of(), 400).size());
    }

    @Test
    void дугаУводитКамеруВБокНоНеМеняетДистанцию() {
        List<Pos> target = wall(0, 9, 6);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.MID_ARC);
        List<CameraShot> shots = ShotPlanner.followShots(target, placement, ShotStyle.MID_ARC, List.of(), 400);

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
        List<CameraShot> shots = ShotPlanner.followShots(target, placement, ShotStyle.ORBIT, List.of(), 300);

        assertEquals(2, shots.size());
        assertTrue(shots.get(0).tick() < shots.get(1).tick());
    }

    @Test
    void целимсяВышеЦентраРадиПравилаТретей() {
        // постройка должна садиться в нижние две трети кадра, а не делить его пополам
        List<Pos> target = wall(0, 9, 20);
        ShotPlanner.Placement placement = plan(target, Set.of(), ShotStyle.MID_FOLLOW);
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
        double floorElevation = plan(floor(0, 15, 15), Set.of(), ShotStyle.MID_FOLLOW).elevation();
        double wallElevation = plan(wall(0, 15, 10), Set.of(), ShotStyle.MID_FOLLOW).elevation();

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
        double elevation = plan(floor(0, 60, 60), Set.of(), ShotStyle.HIGH_HOLD).elevation();
        assertTrue(elevation <= 62.0 + 1.0e-6, "подъём " + elevation + " — это уже вид сверху");
    }

    /** Работа едет слева направо: три окна по три блока. */
    private static List<List<Pos>> movingFront() {
        List<List<Pos>> steps = new ArrayList<>();
        for (int x = 0; x < 30; x++) {
            steps.add(List.of(new Pos(x, 0, 0), new Pos(x, 1, 0), new Pos(x, 2, 0)));
        }
        return steps;
    }

    @Test
    void ведущаяКамераДержитРаботуВЦентреКадра() {
        // Ровно то возражение, из-за которого всё переделывалось: раньше камера целилась
        // в центр слоя, и к концу работа уезжала в угол кадра.
        List<List<Pos>> steps = movingFront();
        List<Pos> targets = new ArrayList<>();
        steps.forEach(targets::addAll);

        List<BuildTimeline.FrontSample> front = BuildTimeline.sample(steps, 0, 600);
        ShotPlanner.Placement start = plan(targets, Set.of(), ShotStyle.MID_FOLLOW);
        List<CameraShot> shots = ShotPlanner.followShots(targets, start, ShotStyle.MID_FOLLOW, front, 600);

        assertTrue(shots.size() >= 3, "на длинный слой должно прийтись несколько кадров");

        for (int i = 0; i < shots.size(); i++) {
            CameraShot shot = shots.get(i);
            double[] aim = front.get(i).center();
            double[] from = {shot.x(), shot.y(), shot.z()};
            double[] dir = CameraFraming.direction(shot.yaw(), shot.pitch());

            // Сравниваем наведение по горизонтали: по вертикали прицел намеренно
            // приподнят по правилу третей, и полное направление отличается на это смещение.
            double dx = aim[0] - from[0], dz = aim[2] - from[2];
            double flat = Math.hypot(dx, dz);
            double dirFlat = Math.hypot(dir[0], dir[2]);

            assertEquals(dx / flat, dir[0] / dirFlat, 1.0e-4, "кадр " + i + ": камера наведена на работу");
            assertEquals(dz / flat, dir[2] / dirFlat, 1.0e-4, "кадр " + i);
        }
    }

    @Test
    void ведущаяКамераПереезжаетВследЗаРаботой() {
        List<List<Pos>> steps = movingFront();
        List<Pos> targets = new ArrayList<>();
        steps.forEach(targets::addAll);

        List<BuildTimeline.FrontSample> front = BuildTimeline.sample(steps, 0, 600);
        ShotPlanner.Placement start = plan(targets, Set.of(), ShotStyle.NEAR_FOLLOW);
        List<CameraShot> shots = ShotPlanner.followShots(targets, start, ShotStyle.NEAR_FOLLOW, front, 600);

        double moved = Math.abs(shots.get(shots.size() - 1).x() - shots.get(0).x());
        assertTrue(moved > 5, "камера должна переехать вслед за фронтом, а не стоять");
    }

    @Test
    void опорныйПланНеВедётИостаётсяОднимКадром() {
        List<List<Pos>> steps = movingFront();
        List<Pos> targets = new ArrayList<>();
        steps.forEach(targets::addAll);

        List<BuildTimeline.FrontSample> front = BuildTimeline.sample(steps, 0, 600);
        ShotPlanner.Placement start = plan(targets, Set.of(), ShotStyle.FAR_HOLD);

        assertEquals(1, ShotPlanner.followShots(targets, start, ShotStyle.FAR_HOLD, front, 600).size());
    }

    @Test
    void направлениеГодитсяИКогдаСлойСамСебяЗаслоняет() {
        // Стена растёт от z=0 к наблюдателю: сначала дальний ряд, потом ближний.
        // Если смотреть только на первый момент, камера встанет там, откуда к концу
        // слоя фронт закроет уже поставленное.
        List<Pos> farRow = wall(0, 9, 6);
        List<Pos> nearRow = wall(3, 9, 6);
        List<Pos> all = new ArrayList<>(farRow);
        all.addAll(nearRow);

        List<ShotPlanner.VisibilityCheck> checks = List.of(
                new ShotPlanner.VisibilityCheck(farRow, Set.of()),
                new ShotPlanner.VisibilityCheck(nearRow, new HashSet<>(farRow)));

        ShotPlanner.Placement placement = ShotPlanner.plan(all, checks,
                ShotStyle.MID_HOLD, 70, 0, Double.NaN);

        // ближний ряд виден только со стороны положительного z — туда камера и обязана уйти
        double[] camera = {placement.shot().x(), placement.shot().y(), placement.shot().z()};
        double visible = Occlusion.visibleFraction(camera, nearRow, new HashSet<>(farRow));
        assertTrue(visible > 0.9, "конец слоя виден только на " + visible);
    }

    @Test
    void облётУрезаетсяПодЧислоКадров() {
        // Полный круг за три кадра сплайн не вытянет: он уведёт камеру мимо цели, и
        // облёт перестанет смотреть на работу. Поэтому дуга урезается под плотность.
        List<List<Pos>> steps = movingFront();
        List<Pos> targets = new ArrayList<>();
        steps.forEach(targets::addAll);

        List<BuildTimeline.FrontSample> front = BuildTimeline.sample(steps, 0, 120);
        ShotPlanner.Placement start = plan(targets, Set.of(), ShotStyle.ORBIT);
        List<CameraShot> shots = ShotPlanner.followShots(targets, start, ShotStyle.ORBIT, front, 120);

        assertTrue(shots.size() < 8, "на короткий слой кадров мало: " + shots.size());

        double[] center = ShotPlanner.centerOf(targets);
        double swept = Math.abs(ShotPlanner.shortestTurn(
                bearing(shots.get(shots.size() - 1), center) - bearing(shots.get(0), center)));
        assertTrue(swept < 180, "за " + shots.size() + " кадров облёт прошёл " + swept + " градусов");
    }

    @Test
    void длинныйСлойПозволяетОблётуРазвернуться() {
        List<List<Pos>> steps = movingFront();
        List<Pos> targets = new ArrayList<>();
        steps.forEach(targets::addAll);

        List<BuildTimeline.FrontSample> longFront = BuildTimeline.sample(steps, 0, 1200);
        List<BuildTimeline.FrontSample> shortFront = BuildTimeline.sample(steps, 0, 120);

        assertTrue(longFront.size() > shortFront.size(),
                "чем длиннее слой, тем больше кадров и тем шире допустимая дуга");
    }

    private static double bearing(CameraShot shot, double[] center) {
        return Math.toDegrees(Math.atan2(shot.x() - center[0], shot.z() - center[2]));
    }

    /** Кольцо стен: четыре стороны по периметру, высота небольшая. */
    private static List<Pos> wallRing(int side, int height) {
        List<Pos> blocks = new ArrayList<>();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                if (x != 0 && x != side - 1 && z != 0 && z != side - 1) {
                    continue;
                }
                for (int y = 0; y < height; y++) {
                    blocks.add(new Pos(x, y, z));
                }
            }
        }
        return blocks;
    }

    @Test
    void кольцоСтенНеСчитаетсяПлоскимПолом() {
        // Настоящая ошибка с практики: по габариту низкое кольцо стен неотличимо от плиты,
        // и камера поднималась над ним как над полом. Занятость пола их разводит.
        double ring = ShotPlanner.flatness(wallRing(12, 3));
        double slab = ShotPlanner.flatness(floor(0, 12, 12));

        assertTrue(ring < 0.2, "кольцо стен посчиталось плоским на " + ring);
        assertTrue(slab > 0.7, "плита должна остаться плоской, а вышло " + slab);
    }

    @Test
    void надСтенамиКамераНеЗадираетсяКакНадПолом() {
        double overRing = plan(wallRing(12, 3), Set.of(), ShotStyle.MID_HOLD).elevation();
        double overSlab = plan(floor(0, 12, 12), Set.of(), ShotStyle.MID_HOLD).elevation();

        assertTrue(overSlab - overRing > 8,
                "над плитой " + overSlab + ", над стенами " + overRing + " — разница мала");
    }

    @Test
    void закрытыйСтенамиСлойСнимаетсяИзнутри() {
        // Интерьер, обнесённый готовыми стенами, снаружи не виден ни с какой стороны.
        // Единственный выход — подойти ближе, вплоть до того, чтобы оказаться внутри.
        List<Pos> interior = new ArrayList<>();
        for (int x = 4; x <= 7; x++) {
            for (int z = 4; z <= 7; z++) {
                interior.add(new Pos(x, 1, z));
            }
        }
        Set<Pos> walls = new HashSet<>(wallRing(12, 5));
        // и крыша сверху, чтобы снаружи не осталось ни одной щели
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                walls.add(new Pos(x, 5, z));
            }
        }

        ShotPlanner.Placement placement = ShotPlanner.plan(interior,
                List.of(new ShotPlanner.VisibilityCheck(interior, walls)),
                ShotStyle.MID_HOLD, 70, 0, Double.NaN);

        double[] camera = {placement.shot().x(), placement.shot().y(), placement.shot().z()};
        double visible = Occlusion.visibleFraction(camera, interior, walls);
        assertTrue(visible > 0.5, "интерьер виден лишь на " + visible + " — камера осталась снаружи");
    }

    @Test
    void дорожкиОднойГруппыРасходятсяПоСторонам() {
        // Настоящая жалоба с практики: статичных планов пять, а ракурс у всех один —
        // отличались только дальностью. Правила композиции у них общие, значит без
        // явного разведения они и должны были выбрать одно и то же.
        List<Pos> target = wallRing(12, 3);
        List<ShotPlanner.VisibilityCheck> checks =
                List.of(new ShotPlanner.VisibilityCheck(target, Set.of()));

        List<Double> taken = new ArrayList<>();
        List<ShotStyle> statics = new ArrayList<>();
        for (ShotStyle style : ShotStyle.values()) {
            if (style.spreadGroup() == 1) {
                statics.add(style);
            }
        }
        assertTrue(statics.size() >= 5, "статичных дорожек должно быть много");

        for (ShotStyle style : statics) {
            ShotPlanner.Placement placement = ShotPlanner.plan(target, checks, style, 70, 0,
                    Double.NaN, List.copyOf(taken));
            taken.add(placement.azimuth());
        }

        // первые несколько обязаны разойтись: дальше сторон просто не хватает
        for (int i = 1; i < Math.min(4, taken.size()); i++) {
            for (int j = 0; j < i; j++) {
                double turn = Math.abs(ShotPlanner.shortestTurn(taken.get(i) - taken.get(j)));
                assertTrue(turn > 30,
                        "дорожки " + i + " и " + j + " встали в " + turn + " градусах друг от друга");
            }
        }
    }

    @Test
    void разведениеНеПересиливаетВидимость() {
        // отойти в сторону полезно, но не ценой того, что снимать станет нечего
        List<Pos> target = wall(0, 9, 6);
        Set<Pos> occluders = new HashSet<>(wall(-6, 21, 14));
        List<ShotPlanner.VisibilityCheck> checks =
                List.of(new ShotPlanner.VisibilityCheck(target, occluders));

        // занимаем сразу несколько ракурсов с той стороны, откуда видно лучше всего
        List<Double> taken = List.of(0.0, 30.0, 330.0);
        ShotPlanner.Placement placement = ShotPlanner.plan(target, checks,
                ShotStyle.MID_HOLD, 70, 0, Double.NaN, taken);

        // Проверяем не координату, а суть: камера могла честно уйти вбок, откуда стену
        // тоже видно. Недопустимо другое — уйти за глухой заслон ради разведения.
        double[] camera = {placement.shot().x(), placement.shot().y(), placement.shot().z()};
        double visible = Occlusion.visibleFraction(camera, target, occluders);
        assertTrue(visible > 0.8, "разведение утащило камеру туда, где видно лишь " + visible);
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

package com.tutorialschematic.client.flashback;

import com.tutorialschematic.camera.BuildAnalyzer;
import com.tutorialschematic.camera.BuildEnvelope;
import com.tutorialschematic.camera.BuildTimeline;
import com.tutorialschematic.camera.CameraFraming;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.LayerShots;
import com.tutorialschematic.camera.SafetyValidator;
import com.tutorialschematic.camera.ScenePlanner;
import com.tutorialschematic.camera.ShotPlanner;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.ModSettings;
import com.tutorialschematic.order.Pos;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Расстановка камер по готовой записи.
 *
 * <p>Собирает вместе три вещи: схему (какие блоки в каком слое), метки из реплея (на каком
 * тике начался каждый слой) и геометрию ({@link ShotPlanner} — откуда слой лучше видно).
 *
 * <p>Метки тут не для красоты: свой счётчик тиков Flashback наружу не отдаёт, поэтому
 * единственный способ узнать, когда именно начался слой, — прочитать метку, которую мы
 * сами же поставили во время записи.
 */
public final class CameraExport {

    /** Подпись метки. Номер слоя потом вычитывается отсюда же. */
    public static final String MARKER_PREFIX = "Слой ";
    /** Метка конца постройки — даёт последнему слою момент окончания для пролётов. */
    public static final String MARKER_END = "Постройка завершена";


    private CameraExport() {
    }

    /**
     * Метка на старте слоя: подпись с номером и именем, цвет — цвет самого слоя.
     *
     * <p>Уходит на клиентский поток: постройка идёт на серверном, а запись Flashback
     * клиентская, и трогать её оттуда напрямую нельзя.
     */
    public static void markLayerStart(int index, BuildLayer layer) {
        String description = MARKER_PREFIX + (index + 1) + ": " + layer.name();
        int colour = layer.color();
        Minecraft.getInstance().execute(() -> FlashbackBridge.addMarker(colour, description));
    }

    public static void markBuildFinished() {
        Minecraft.getInstance().execute(() -> FlashbackBridge.addMarker(0xFFFFFF, MARKER_END));
    }

    /**
     * Читает свежий реплей и пишет к нему дорожки камер.
     *
     * @return путь к файлу камер либо {@code null}, если не вышло — причина уходит в чат
     */
    @Nullable
    public static Path exportForNewestReplay() {
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic == null) {
            EditorState.error("Нет открытой схемы");
            return null;
        }
        if (!FlashbackBridge.isAvailable()) {
            EditorState.error("Flashback не найден — расставлять камеры некуда");
            return null;
        }
        if (FlashbackBridge.isRecording()) {
            EditorState.error("Сначала остановите запись: пока она идёт, реплей ещё не дописан");
            return null;
        }

        Path replay = ReplayMetadata.newestReplay();
        if (replay == null) {
            EditorState.error("В папке Flashback нет ни одного реплея");
            return null;
        }
        ReplayMetadata meta = ReplayMetadata.read(replay);
        if (meta == null) {
            EditorState.error("Не удалось прочитать метаданные реплея " + replay.getFileName());
            return null;
        }

        List<LayerTiming> timings = matchLayers(schematic, meta);
        if (timings.isEmpty()) {
            EditorState.error("В реплее нет наших меток — записывали не эту постройку "
                    + "или запись шла без мода");
            return null;
        }

        // Между записью и расстановкой камер могли выйти из игры — журнал замеров лежит
        // на диске и переживает это.
        BuildTicks.loadIfEmpty();

        // Тот же угол, по которому подбирали расстояние до стены, уходит и в настройки
        // записи — иначе рендер возьмёт свой, и кадр разъедется с расчётом.
        double fov = Minecraft.getInstance().options.fov().get();
        Map<ShotStyle, List<CameraShot>> tracks = planTracks(schematic, timings, fov);
        Path written = EditorStateWriter.write(meta.uuid(), tracks, fov, TARGET_ASPECT,
                ModSettings.get().replayTimeOfDay());
        if (written == null) {
            EditorState.error("Камеры не записаны — состояние редактора для этого реплея уже есть");
            return null;
        }

        int totalShots = tracks.values().stream().mapToInt(List::size).sum();
        // Суммарное число кадров — быстрый способ заметить, что смотришь не свежий пересчёт:
        // если после правки алгоритма число не изменилось, экспорт либо не запускался
        // заново, либо файл кто-то переписал поверх (см. NOTES.md про автосохранение Flashback).
        EditorState.info("Камеры расставлены: " + timings.size() + " слоёв, "
                + tracks.size() + " дорожек, " + totalShots + " кадров"
                + (BuildTicks.hasMeasurements() ? ", время замеренное" : ", время по меткам")
                + ". Реплей: " + replay.getFileName());
        return written;
    }

    /** Слой вместе с тем, когда он начался и когда кончился, в тиках записи. */
    private record LayerTiming(BuildLayer layer, int index, int startTick, int endTick) {
    }

    /**
     * Сопоставляет метки из реплея со слоями схемы. Номер слоя берётся из подписи метки,
     * поэтому переставленные или переименованные слои не собьют привязку.
     */
    private static List<LayerTiming> matchLayers(TutorialSchematic schematic, ReplayMetadata meta) {
        List<int[]> found = new ArrayList<>();
        int endTick = -1;

        for (Map.Entry<Integer, String> entry : meta.markers().entrySet()) {
            String description = entry.getValue();
            if (MARKER_END.equals(description)) {
                endTick = entry.getKey();
                continue;
            }
            if (!description.startsWith(MARKER_PREFIX)) {
                continue;
            }
            int colon = description.indexOf(':');
            if (colon < 0) {
                continue;
            }
            try {
                int number = Integer.parseInt(description.substring(MARKER_PREFIX.length(), colon).trim());
                found.add(new int[]{entry.getKey(), number - 1});
            } catch (NumberFormatException ignored) {
                // чужая метка с похожей подписью — просто пропускаем
            }
        }

        List<LayerTiming> result = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            int startTick = found.get(i)[0];
            int layerIndex = found.get(i)[1];
            BuildLayer layer = schematic.layerAt(layerIndex);
            if (layer == null || layer.isEmpty()) {
                continue;
            }
            int finish = i + 1 < found.size() ? found.get(i + 1)[0] : endTick;
            if (finish <= startTick) {
                // последний слой без метки конца — даём ему условные десять секунд
                finish = startTick + 200;
            }
            result.add(new LayerTiming(layer, layerIndex, startTick, finish));
        }
        return result;
    }

    private static Map<ShotStyle, List<CameraShot>> planTracks(TutorialSchematic schematic,
                                                               List<LayerTiming> timings,
                                                               double fov) {
        Map<ShotStyle, List<CameraShot>> tracks = new EnumMap<>(ShotStyle.class);
        for (ShotStyle style : ShotStyle.values()) {
            tracks.put(style, new ArrayList<>());
        }

        // Общий план ставится один раз на всю запись и кадрируется по всей постройке
        // целиком — к нему возвращаются, чтобы увидеть, насколько дом вырос.
        List<Pos> everything = new ArrayList<>();
        for (LayerTiming timing : timings) {
            everything.addAll(positionsOf(timing.layer()));
        }

        // Два разных множества заслонов, и смешивать их нельзя. built — только блоки уже
        // построенных слоёв: это то, из-за чего поиск ракурса имеет право решить «отсюда не
        // видно, надо зайти внутрь» — если самой постройкой заслонило, значит мы правда
        // внутри чего-то похожего на помещение. solid — то же самое плюс постороннее из
        // мира (дерево, рельеф, соседнее здание): такое ограничивает картинку, но не
        // повод лезть внутрь дома, который мы и снимаем. Дерево у стены не значит «мы
        // внутри», значит просто «с этой стороны деревом прикрыло» — solid идёт только
        // в проверки и в финальную защиту от врезания, никогда в сам поиск.
        Set<Pos> built = new HashSet<>();
        Set<Pos> solid = new HashSet<>(worldOccluders(schematic, new HashSet<>(everything)));

        // Габарит всей будущей постройки — граница «снаружи, как оператор». Считается один
        // раз по всей схеме: оператор с первого слоя держится за линией застройки, а не
        // переезжает, когда стены дорастут до его точки. Внутрь габарита камера имеет право
        // зайти только ради интерьера, которого снаружи не видно вовсе (см. ниже).
        BuildEnvelope envelope = everything.isEmpty() ? null : BuildEnvelope.around(everything);

        for (ShotStyle style : ShotStyle.values()) {
            if (style.wholeBuild() && !everything.isEmpty()) {
                tracks.get(style).add(ShotPlanner.plan(everything,
                        List.of(new ShotPlanner.VisibilityCheck(everything, built)), style, fov,
                        timings.get(0).startTick(), Double.NaN, List.of(), List.of(),
                        TARGET_ASPECT, envelope).shot());
            }
        }
        // Дальше — покадровая съёмка по слоям. Слой это фигура: пол и потолок — лист,
        // стены — кольцо. Кольцо, которое кладут стороной за стороной, распадается на эти
        // стороны, и каждую снимают снаружи напротив неё. Разбор общий для всех дорожек:
        // снимают они одно и то же, отличаясь только крупностью и наклоном.
        List<LayerShots.Unit> units = new ArrayList<>();
        int globalStep = 0;
        // Стороны слоя ищутся по силуэту всей постройки, а не одного слоя: стена, у которой
        // под ней уже стоит столб, — часть той же грани, что и столб, и уезжать с неё рано.
        List<Pos> done = new ArrayList<>();
        for (LayerTiming timing : timings) {
            List<List<Pos>> steps = timing.layer().steps();
            BuildLayer layer = timing.layer();

            // Куски раскладываются по времени, когда слой действительно строится, а не по
            // всему промежутку между метками: задержки в начале и в конце — мёртвое время,
            // и если размазать куски по нему, каждая смена ракурса уезжает на полсекунды.
            int buildStart = timing.startTick() + layer.startDelayTicks();
            int buildEnd = Math.max(buildStart + 1, timing.endTick() - layer.endDelayTicks());
            List<LayerShots.Unit> layerUnits =
                    LayerShots.split(steps, buildStart, buildEnd, globalStep, done);

            // Нарезка знает только номера шагов; когда эти шаги встали на самом деле, знает
            // журнал замеров. Если он от этой записи — время ракурсов берётся оттуда, и
            // граница перестаёт зависеть от того, ровно ли шла кладка.
            layerUnits = BuildTicks.retime(timing.index(), layerUnits, globalStep, steps.size(),
                    timing.startTick(), buildEnd);

            // А вот первый кадр слоя должен стоять уже на метке — задержка в начале для того
            // и нужна, чтобы камера успела встать до первого блока.
            if (!layerUnits.isEmpty()) {
                LayerShots.Unit first = layerUnits.get(0);
                layerUnits.set(0, new LayerShots.Unit(first.blocks(), first.steps(), first.form(),
                        first.facing(), timing.startTick(), first.endTick(), first.firstGlobalStep()));
            }
            units.addAll(layerUnits);
            globalStep += steps.size();
            for (List<Pos> step : steps) {
                done.addAll(step);
            }
        }

        for (LayerShots.Unit unit : units) {
            // Заслоны берём на момент начала куска: к его концу часть блоков поставит он сам,
            // и требовать, чтобы они не мешали, значит требовать невозможного.
            Set<Pos> standing = Set.copyOf(built);
            Set<Pos> obstacles = Set.copyOf(solid);

            for (ShotStyle style : ShotStyle.values()) {
                if (style.wholeBuild() || !style.exported()) {
                    continue;
                }
                CameraShot shot = LayerShots.place(unit, style, fov, TARGET_ASPECT,
                        standing, obstacles, envelope, unit.startTick());
                if (shot == null) {
                    continue;
                }
                tracks.get(style).add(shot);
                if (style.follows()) {
                    // Ведущая дорожка стоит на том же месте, но доводит объектив за работой:
                    // это то, что делает оператор, и то, чего прежняя схема выразить не могла —
                    // там камера считалась вокруг прицела и уезжала вместе с ним.
                    tracks.get(style).addAll(followWithin(unit, shot));
                }
            }
            built.addAll(unit.blocks());
            solid.addAll(unit.blocks());
        }

        return tracks;
    }

    /**
     * Кадры внутри куска для ведущей дорожки: камера не двигается, меняется только наводка.
     */
    private static List<CameraShot> followWithin(LayerShots.Unit unit, CameraShot from) {
        List<CameraShot> extra = new ArrayList<>();
        List<BuildTimeline.FrontSample> front =
                BuildTimeline.sample(unit.steps(), unit.startTick(), unit.endTick() - 1);
        double[] camera = {from.x(), from.y(), from.z()};
        for (BuildTimeline.FrontSample sample : front) {
            if (sample.tick() <= from.tick()) {
                continue;
            }
            float[] angles = CameraFraming.lookAt(camera, sample.center());
            extra.add(new CameraShot(sample.tick(), camera[0], camera[1], camera[2],
                    angles[0], angles[1], false));
        }
        return extra;
    }

    /** Кадры одной сцены вместе с ракурсом, из которого они получены — нужен следующей сцене. */
    private record SceneResult(List<CameraShot> shots, ShotPlanner.Placement placement) {
    }

    /**
     * Ищет сцене настоящий ракурс композиционным поиском и раскладывает его на кадры.
     *
     * <p>Поиск смотрит только на блоки схемы ({@code occludersAtStart}) — дерево или рельеф
     * не должны заставлять его решить, что мы внутри помещения, и полезть камерой в дом.
     * А вот финальная раскладка на кадры ({@code followShots}) уже получает
     * {@code solidAtStart} — от дерева камере всё равно нужно физически не залезать.
     *
     * @param direction направление фронта постройки этой сцены (или унаследованное от
     *                  предыдущей, для столба) — {@code NaN}, если своей стороны нет
     *                  (пол, одинокий столб без соседей): тогда поиск идёт по всему кругу
     */
    private static SceneResult shootScene(ScenePlanner.Scene scene, double direction, ShotStyle style, double fov,
                                          List<ShotPlanner.VisibilityCheck> checks, Set<Pos> solidAtStart,
                                          double previousAzimuth, BuildEnvelope keepOutsideOf) {
        List<Pos> targets = scene.blocks();

        // У стены (и у кольца интерьера вдоль той же стены) есть ровно две осмысленные
        // стороны — перпендикуляр к тому, как едет фронт кладки, в одну сторону и в другую.
        // Снаружи или изнутри — решает не жёсткое правило, а та же проверка видимости, что
        // и так уже есть в поиске: снаружи стены открыто — выигрывает наружная сторона,
        // изнутри уже обнесённой комнаты открыто внутрь — выигрывает внутренняя.
        List<Double> azimuthAnchors = Double.isNaN(direction)
                ? List.of()
                : List.of(norm(direction + 90), norm(direction + 270));

        ShotPlanner.Placement placement = ShotPlanner.plan(targets, checks, style, fov,
                scene.startTick(), previousAzimuth, List.of(), azimuthAnchors, TARGET_ASPECT,
                keepOutsideOf);

        List<BuildTimeline.FrontSample> front = style.follows()
                ? BuildTimeline.sample(scene.steps(), scene.startTick(), scene.endTick() - 1)
                : List.of();
        List<CameraShot> shots = ShotPlanner.followShots(targets, placement, style, front,
                scene.endTick() - 1, solidAtStart, keepOutsideOf);
        return new SceneResult(shots, placement);
    }

    /**
     * Соотношение сторон итогового видео: сейчас вертикальное, под шортсы.
     *
     * <p>Кадрирование от него зависит напрямую — вертикальный кадр почти вдвое уже
     * горизонтального, и то, что спокойно помещалось в 16:9, в шортсе оказывается за краем.
     */
    private static final double TARGET_ASPECT = 9.0 / 16.0;


    private static double norm(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    private static List<Pos> positionsOf(BuildLayer layer) {
        List<Pos> result = new ArrayList<>(layer.blockCount());
        for (BlockPos pos : layer.blocks().keySet()) {
            result.add(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        }
        return result;
    }

    /** Насколько шире габарита постройки сканировать мир — с запасом под самую дальнюю камеру. */
    private static final int WORLD_SCAN_MARGIN = 20;

    /**
     * Смотрит в реальный мир вокруг постройки и собирает всё непрозрачное, что не входит
     * ни в один слой схемы — дерево, рельеф, соседнее здание. Раньше камера про такое ничего
     * не знала: заслонами считались только блоки самой схемы, а дерево рядом с домом для неё
     * будто не существовало.
     *
     * @param schematicBlocks все блоки схемы разом (по всем слоям) — их пропускаем, это не
     *                        посторонние заслоны, а сама постройка, её место в занятости
     *                        учитывается отдельно, по мере того как слои реально ложатся
     */
    private static Set<Pos> worldOccluders(TutorialSchematic schematic, Set<Pos> schematicBlocks) {
        Level level = Minecraft.getInstance().level;
        if (level == null || schematicBlocks.isEmpty()) {
            return Set.of();
        }

        BlockPos origin = schematic.origin();
        int[] size = schematic.size();
        int minX = origin.getX() - WORLD_SCAN_MARGIN;
        // Ниже нижней точки постройки специально не лезем: там ровный рельеф, на котором
        // она стоит, тянется во все стороны и на низких ракурсах закрывает почти всё —
        // поиск решает, что кругом стена, и жмётся камерой вплотную. Дерево или соседнее
        // здание при этом никуда не денутся: они выше уровня земли, а не ниже.
        int minY = origin.getY();
        int minZ = origin.getZ() - WORLD_SCAN_MARGIN;
        int maxX = origin.getX() + size[0] + WORLD_SCAN_MARGIN;
        int maxY = origin.getY() + size[1] + WORLD_SCAN_MARGIN;
        int maxZ = origin.getZ() + size[2] + WORLD_SCAN_MARGIN;

        Set<Pos> result = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Pos pos = new Pos(x, y, z);
                    if (schematicBlocks.contains(pos)) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).isAir()) {
                        result.add(pos);
                    }
                }
            }
        }
        return result;
    }
}

package com.tutorialschematic.client.flashback;

import com.tutorialschematic.camera.BuildTimeline;
import com.tutorialschematic.camera.CameraFraming;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.Occlusion;
import com.tutorialschematic.camera.ShotPlanner;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.client.EditorState;
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

        Map<ShotStyle, List<CameraShot>> tracks = planTracks(schematic, timings);
        Path written = EditorStateWriter.write(meta.uuid(), tracks);
        if (written == null) {
            EditorState.error("Камеры не записаны — состояние редактора для этого реплея уже есть");
            return null;
        }

        int totalShots = tracks.values().stream().mapToInt(List::size).sum();
        // Суммарное число кадров — быстрый способ заметить, что смотришь не свежий пересчёт:
        // если после правки алгоритма число не изменилось, экспорт либо не запускался
        // заново, либо файл кто-то переписал поверх (см. NOTES.md про автосохранение Flashback).
        EditorState.info("Камеры расставлены: " + timings.size() + " слоёв, "
                + tracks.size() + " дорожек, " + totalShots + " кадров. Реплей: " + replay.getFileName());
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

    /**
     * Подстраховка при накоплении фазы: если с её примерного ракурса новое окно видно меньше
     * этого — режем, даже если фронт по углу почти не сдвинулся. Сам по себе угол ничего не
     * знает про заслоны от других слоёв или про форму, которая перестала быть выпуклой без
     * явного поворота (например, фронт зашёл за угол пристройки).
     */
    private static final double PHASE_CONTINUITY_VISIBILITY = 0.7;

    /**
     * Главный триггер новой фазы: на сколько градусов должен сдвинуться фронт (по азимуту
     * от центра **всей постройки**, не слоя) — «широкая» гранулярность режет редко и
     * объединяет, «дробящая» режет часто. Не про видимость — ровно то, что показал ручной
     * эталон: пока кладка идёт по одной стене, азимут меняется мало, стена сменилась —
     * скачок сразу на десятки градусов.
     */
    private static final double WIDE_CUT_ANGLE = 65.0;
    private static final double CLOSE_CUT_ANGLE = 30.0;

    /**
     * Меньше этого фаза не закрывается, что бы ни говорили угол, видимость или плоскостность.
     * На реальном рендере «Ближнего · статичного» дробящая гранулярность резала на такие
     * мелкие куски (обрезок одного столбика), что камера вставала вплотную и временами
     * оказывалась внутри блока — снаружи в упор ставить её было уже некуда. Больше блоков
     * в фазе — больше её радиус — есть куда отступить.
     */
    private static final int MIN_PHASE_BLOCKS = 8;

    /**
     * Второй, независимый от угла триггер новой фазы: насколько должна перемениться
     * «плоскостность» ({@link ShotPlanner#flatness}) нового окна относительно уже
     * накопленного, чтобы считать, что здесь нужен другой подъём камеры. Пол, каркас
     * (столбы) и стены могут идти с одного и того же азимута (фронт не поворачивается),
     * но это три разных по форме куска — на записи-эталоне ровно на этих переходах камера
     * меняла высоту, хотя не поворачивалась.
     */
    private static final double FLATNESS_CUT_DELTA = 0.35;

    private static Map<ShotStyle, List<CameraShot>> planTracks(TutorialSchematic schematic,
                                                               List<LayerTiming> timings) {
        double fov = Minecraft.getInstance().options.fov().get();
        Map<ShotStyle, List<CameraShot>> tracks = new EnumMap<>(ShotStyle.class);
        Map<ShotStyle, Double> lastAzimuth = new EnumMap<>(ShotStyle.class);
        for (ShotStyle style : ShotStyle.values()) {
            tracks.put(style, new ArrayList<>());
            lastAzimuth.put(style, Double.NaN);
        }

        // Общий план ставится один раз на всю запись и кадрируется по всей постройке
        // целиком — к нему возвращаются, чтобы увидеть, насколько дом вырос.
        List<Pos> everything = new ArrayList<>();
        for (LayerTiming timing : timings) {
            everything.addAll(positionsOf(timing.layer()));
        }
        double[] buildCenter = ShotPlanner.centerOf(everything);

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

        for (ShotStyle style : ShotStyle.values()) {
            if (style.wholeBuild() && !everything.isEmpty()) {
                tracks.get(style).add(ShotPlanner.plan(everything, built, style, fov,
                        timings.get(0).startTick(), Double.NaN).shot());
            }
        }

        // Единый таймлайн окон по всей записи, а не по одному слою за раз. Граница слоя
        // сама по себе ракурс больше не меняет — раньше конец каждого слоя безусловно резал
        // дорожку на новый кадр, даже если следующий слой прекрасно снимался бы с того же
        // ракурса без единого реза. Решает только реальный сдвиг фронта.
        List<BuildTimeline.Window> allWindows = new ArrayList<>();
        for (LayerTiming timing : timings) {
            allWindows.addAll(BuildTimeline.windows(timing.layer().steps(), timing.startTick(), timing.endTick()));
        }

        Map<ShotStyle, PhaseAccumulator> phases = new EnumMap<>(ShotStyle.class);
        for (ShotStyle style : ShotStyle.values()) {
            if (style.wholeBuild() || !style.exported()) {
                continue;
            }
            phases.put(style, new PhaseAccumulator());
        }

        for (BuildTimeline.Window window : allWindows) {
            List<Pos> blocks = window.blocks();
            double windowAzimuth = frontAzimuth(ShotPlanner.centerOf(blocks), buildCenter);

            for (Map.Entry<ShotStyle, PhaseAccumulator> entry : phases.entrySet()) {
                ShotStyle style = entry.getKey();
                PhaseAccumulator phase = entry.getValue();
                double cutAngle = style.granularity() == ShotStyle.Granularity.PREFER_WHOLE
                        ? WIDE_CUT_ANGLE : CLOSE_CUT_ANGLE;

                boolean startNew;
                boolean hardCut;
                if (phase.isEmpty()) {
                    startNew = true;
                    hardCut = true;
                } else {
                    double turn = Math.abs(ShotPlanner.shortestTurn(windowAzimuth - phase.referenceAzimuth));
                    if (turn >= cutAngle) {
                        startNew = true;
                        hardCut = true;
                    } else {
                        // Примерная камера для подстраховки: настоящий ракурс фазы ещё не
                        // посчитан (он ищется только когда фаза закрывается), но грубая оценка
                        // по накопленным блокам и типовой высоте дорожки достаточно точна,
                        // чтобы поймать «а тут вдруг что-то заслонило».
                        List<Pos> prospective = new ArrayList<>(phase.blocksSoFar);
                        prospective.addAll(blocks);
                        double[] prospectiveCenter = ShotPlanner.centerOf(prospective);
                        double roughRadius = ShotPlanner.radiusOf(prospective, prospectiveCenter);
                        double roughDistance = CameraFraming.distanceFor(roughRadius, fov, style.margin());
                        double roughElevation = (style.minElevation() + style.maxElevation()) / 2;
                        double[] camera = CameraFraming.positionAround(prospectiveCenter, roughDistance,
                                phase.referenceAzimuth, roughElevation);
                        // Тут — видно ли вообще, а не «внутри ли мы», поэтому мир учитывается.
                        if (Occlusion.visibleFraction(camera, blocks, solid) < PHASE_CONTINUITY_VISIBILITY) {
                            startNew = true;
                            hardCut = true;
                        } else {
                            // Тот же азимут, но фронт сменил форму (пол → каркас → стены) —
                            // нужна другая высота, хотя сторона та же. У ведущей камеры это
                            // повод плавно переехать (так и было на эталоне), у остальных —
                            // всё равно жёсткий рез, там плавных переездов вообще нет.
                            double delta = Math.abs(ShotPlanner.flatness(blocks)
                                    - ShotPlanner.flatness(phase.blocksSoFar));
                            startNew = delta >= FLATNESS_CUT_DELTA;
                            hardCut = !style.follows();
                        }
                    }
                }

                // Меньше минимума — фаза ещё не набрала достаточно блоков, чтобы у нового
                // ракурса было куда отступить. Копим дальше, что бы ни решили триггеры выше.
                if (startNew && !phase.isEmpty() && phase.blocksSoFar.size() < MIN_PHASE_BLOCKS) {
                    startNew = false;
                }

                if (startNew) {
                    finishPhase(phase, style, tracks, fov, lastAzimuth, window.tick());
                    phase.reset(windowAzimuth, window.tick(), hardCut, built, solid);
                }
                phase.add(window);
            }
            built.addAll(blocks);
            solid.addAll(blocks);
        }

        int finalTick = timings.get(timings.size() - 1).endTick();
        for (Map.Entry<ShotStyle, PhaseAccumulator> entry : phases.entrySet()) {
            finishPhase(entry.getValue(), entry.getKey(), tracks, fov, lastAzimuth, finalTick);
        }

        return tracks;
    }

    /**
     * Копится, пока фронт постройки остаётся достаточно на одном месте. Ракурс фазы не
     * известен, пока она не закрылась — {@code referenceAzimuth} нужен только для решения
     * «резать или нет», настоящий ракурс ищет {@link #finishPhase} композиционным поиском.
     */
    private static final class PhaseAccumulator {
        double referenceAzimuth = Double.NaN;
        int startTick;
        /** Жёсткая склейка в начало этой фазы — false означает плавный переезд с прошлой. */
        boolean hardCut = true;
        /** Только блоки схемы — уходит в поиск ракурса, чтобы дерево не выглядело как «мы внутри». */
        Set<Pos> occludersAtStart = Set.of();
        /** Блоки схемы плюс постороннее из мира — уходит в финальную защиту от врезания. */
        Set<Pos> solidAtStart = Set.of();
        final List<List<Pos>> steps = new ArrayList<>();
        final List<Pos> blocksSoFar = new ArrayList<>();

        boolean isEmpty() {
            return steps.isEmpty();
        }

        void add(BuildTimeline.Window window) {
            steps.addAll(window.steps());
            blocksSoFar.addAll(window.blocks());
        }

        void reset(double azimuth, int tick, boolean hardCut, Set<Pos> built, Set<Pos> solid) {
            referenceAzimuth = azimuth;
            startTick = tick;
            this.hardCut = hardCut;
            occludersAtStart = new HashSet<>(built);
            solidAtStart = new HashSet<>(solid);
            steps.clear();
            blocksSoFar.clear();
        }
    }

    /**
     * Закрывает фазу: ищет ей настоящий ракурс композиционным поиском (не по фронту — тот
     * решал только когда резать, а не куда смотреть) и раскладывает на кадры.
     *
     * <p>Поиск смотрит только на блоки схемы ({@code occludersAtStart}) — то же разделение,
     * что и у самозаслона: дерево или рельеф не должны заставлять поиск решить, что мы
     * внутри помещения, и полезть камерой в дом. А вот финальная раскладка на кадры
     * ({@code followShots}) уже получает {@code solidAtStart} — от дерева камере всё равно
     * нужно физически не залезать.
     */
    private static void finishPhase(PhaseAccumulator phase, ShotStyle style,
                                    Map<ShotStyle, List<CameraShot>> tracks, double fov,
                                    Map<ShotStyle, Double> lastAzimuth, int endTick) {
        if (phase.isEmpty()) {
            return;
        }
        List<Pos> phaseTargets = phase.blocksSoFar;
        ShotPlanner.VisibilityCheck check = new ShotPlanner.VisibilityCheck(phaseTargets, phase.occludersAtStart);
        ShotPlanner.Placement placement = ShotPlanner.plan(phaseTargets, List.of(check), style, fov,
                phase.startTick, lastAzimuth.get(style), List.of());
        lastAzimuth.put(style, placement.azimuth());

        List<BuildTimeline.FrontSample> front = style.follows()
                ? BuildTimeline.sample(phase.steps, phase.startTick, endTick - 1)
                : List.of();
        List<CameraShot> shots = ShotPlanner.followShots(phaseTargets, placement, style, front,
                endTick - 1, phase.solidAtStart);
        if (!shots.isEmpty()) {
            shots.set(0, shots.get(0).withCut(phase.hardCut));
            tracks.get(style).addAll(shots);
        }
    }

    /** Азимут направления от центра постройки на центр окна — та же система отсчёта, что у {@link CameraFraming}. */
    private static double frontAzimuth(double[] windowCenter, double[] buildCenter) {
        double dx = windowCenter[0] - buildCenter[0];
        double dz = windowCenter[2] - buildCenter[2];
        if (Math.abs(dx) < 1.0e-6 && Math.abs(dz) < 1.0e-6) {
            return 0;
        }
        double degrees = Math.toDegrees(Math.atan2(dx, dz));
        return degrees < 0 ? degrees + 360 : degrees;
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

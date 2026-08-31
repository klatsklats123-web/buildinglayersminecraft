package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Собирает участки работы ({@link BuildAnalyzer.WorkSegment}) в сцены — то, на что реально
 * встаёт ракурс — под правила одной конкретной дорожки.
 *
 * <p>{@link BuildAnalyzer} ничего не знает о камерах и режет только там, где физически
 * поменялась форма или направление фронта. Но одна и та же геометрия должна сниматься
 * по-разному разными дорожками: дальний план вправе объединить несколько соседних участков
 * (обошли угол дома — а всё ещё один кадр), крупный план обязан резать почти на каждом из
 * них. Это и есть работа {@link ShotPolicy} — разница не в алгоритме, а в допуске.
 */
public final class ScenePlanner {

    private ScenePlanner() {
    }

    /**
     * Сцена — то, что снимается одним ракурсом (или одним ведением прицела, если дорожка
     * следит за работой).
     *
     * @param shape           форма сцены — общая для всех участков внутри неё (иначе они не
     *                        слились бы), определяет, как выбирать ракурс
     * @param direction       направление фронта постройки, если оно есть; {@code NaN} для
     *                        {@code FLAT} и {@code VERTICAL} — там направление ничего не значит
     * @param firstGlobalStep индекс первого шага сцены в исходном списке шагов всей записи —
     *                        нужен только чтобы найти снимок заслонов на момент начала сцены
     */
    public record Scene(List<Pos> blocks, List<List<Pos>> steps, double[] center, double radius,
                        BuildAnalyzer.Shape shape, double direction,
                        int startTick, int endTick, int firstGlobalStep) {
    }

    public static List<Scene> buildScenes(List<BuildAnalyzer.WorkSegment> segments, ShotPolicy policy) {
        List<Scene> merged = new ArrayList<>();
        if (segments.isEmpty()) {
            return merged;
        }

        Accumulator acc = new Accumulator();
        for (BuildAnalyzer.WorkSegment segment : segments) {
            if (acc.isEmpty()) {
                acc.start(segment);
                continue;
            }

            // Направление сравнивается, если оно вообще определено у обоих (BuildAnalyzer уже
            // не отдаёт его для FLAT/VERTICAL) — не только у LINEAR: участку, которому не
            // хватило вытянутости на строгий LINEAR, но который явно едет в сторону (BLOB с
            // направлением), тоже нельзя молча слиться с соседом, укатившимся совсем в другую
            // сторону — иначе сцена объединит буквально разные стороны постройки.
            boolean directionBreak = !Double.isNaN(acc.direction) && !Double.isNaN(segment.direction())
                    && Math.abs(ShotPlanner.shortestTurn(segment.direction() - acc.direction))
                            > policy.mergeAngleTolerance();
            boolean shapeBreak = acc.shape != segment.shape();

            boolean sizeBreak = false;
            if (!directionBreak && !shapeBreak) {
                List<Pos> combined = new ArrayList<>(acc.blocks);
                combined.addAll(segment.blocks());
                double[] centre = ShotPlanner.centerOf(combined);
                sizeBreak = ShotPlanner.radiusOf(combined, centre) > policy.maxSceneRadius();
            }

            boolean tooSmallToBreak = acc.blockCount < policy.minSceneBlocks();
            if ((directionBreak || shapeBreak || sizeBreak) && !tooSmallToBreak) {
                merged.add(acc.finish());
                acc = new Accumulator();
                acc.start(segment);
            } else {
                acc.add(segment);
            }
        }
        if (!acc.isEmpty()) {
            merged.add(acc.finish());
        }

        // Один участок сам по себе может быть больше допустимого радиуса дорожки (сплошной
        // пол под ближний план) — слияние такого не касалось, оно только не давало соседям
        // прирасти. Дорезаем такие сцены отдельно, по их же собственным шагам.
        List<Scene> result = new ArrayList<>();
        for (Scene scene : merged) {
            result.addAll(sliceIfTooBig(scene, policy));
        }
        return result;
    }

    /** На сколько моментов по ходу сцены разбивается проверка видимости. */
    private static final int SCENE_SLICES = 5;

    /**
     * Разбивает сцену на несколько моментов её постройки: что кладётся именно в этот момент и
     * что к этому моменту уже стоит.
     *
     * <p>Это ответ на «столбы перекрывают стены»: {@link ShotPlanner} берёт по всем моментам
     * <b>худшую</b> видимость, поэтому направление, из которого в начале сцены видно всё, а к
     * середине уже ничего, проигрывает — хотя по одному снимку на старте выглядело бы идеальным.
     *
     * @param builtBeforeStep что из схемы стоит перед каждым шагом всей записи
     */
    public static List<ShotPlanner.VisibilityCheck> timeSlices(Scene scene, List<Set<Pos>> builtBeforeStep) {
        List<List<Pos>> steps = scene.steps();
        int total = steps.size();
        int slices = Math.max(1, Math.min(SCENE_SLICES, total));

        List<ShotPlanner.VisibilityCheck> checks = new ArrayList<>(slices);
        for (int i = 0; i < slices; i++) {
            int from = (int) ((long) i * total / slices);
            int to = (int) ((long) (i + 1) * total / slices);

            List<Pos> placedNow = new ArrayList<>();
            for (int s = from; s < to; s++) {
                placedNow.addAll(steps.get(s));
            }
            if (placedNow.isEmpty()) {
                continue;
            }
            int globalStep = scene.firstGlobalStep() + from;
            Set<Pos> standing = globalStep < builtBeforeStep.size()
                    ? builtBeforeStep.get(globalStep) : Set.of();
            checks.add(new ShotPlanner.VisibilityCheck(placedNow, standing));
        }
        return checks;
    }

    /** Ниже этой достижимой видимости сцена считается неснимаемой одним кадром и режется. */
    private static final double SPLIT_VISIBILITY = 0.75;
    /** Сколько раз подряд разрешено делить одну сцену пополам. */
    private static final int MAX_SPLIT_DEPTH = 3;
    /** Направлений в грубой разведке видимости — полный круг, но редкой сеткой. */
    private static final int PROBE_AZIMUTHS = 12;

    /**
     * Дорезает сцены, которые не снять одним кадром ни с какой стороны.
     *
     * <p>Нарезка по форме и направлению фронта ничего не знает о заслонах: кусок работы может
     * быть цельным по геометрии и при этом наполовину прятаться за тем, что уже построено.
     * На реальной постройке такие сцены давали больше половины всей заслонённости, оставаясь
     * при этом единственным кадром — камера честно выбирала лучшее из плохого.
     *
     * <p>Принцип простой и ровно тот, по которому работает оператор: <b>если с одной точки не
     * видно всё — это два кадра, а не один</b>. Проверка грубая (редкая сетка направлений на
     * типовой высоте), потому что решает она не куда встать, а только надо ли делить; ракурс
     * каждой половине потом ищется как обычно, полным поиском.
     */
    public static List<Scene> splitWhatCannotBeSeen(List<Scene> scenes, List<Set<Pos>> builtBeforeStep,
                                                    ShotStyle style, double fovDegrees, ShotPolicy policy) {
        return splitWhatCannotBeSeen(scenes, builtBeforeStep, style, fovDegrees, policy, null);
    }

    /**
     * То же, но с позицией оператора: разведка видимости стоит снаружи габарита постройки,
     * как встанет и настоящая камера.
     *
     * <p>Это принципиально для правильного вердикта. Разведка без ограничения отвечает на
     * вопрос «видно ли сцену хоть откуда-нибудь», и для кольца стен ответ всегда «да — из
     * центра комнаты»: сцена оставалась одним кадром, а камера вслед за этим вердиктом
     * лезла внутрь дома. Снаружи же кольцо само себя заслоняет — и честный ответ «одним
     * кадром снаружи не снять» превращает его в несколько кадров с разных сторон, ровно
     * как в референсных роликах.
     *
     * <p>Сцена, которую снаружи не видно вообще ни с какой стороны ({@link #visibleFromOutside}),
     * — это интерьер за уже готовыми стенами: её не режем на осколки, а оставляем как есть,
     * и снимать её будут изнутри, без ограничения габаритом.
     *
     * @param envelope габарит постройки; {@code null} — прежнее поведение, без понятия «снаружи»
     */
    public static List<Scene> splitWhatCannotBeSeen(List<Scene> scenes, List<Set<Pos>> builtBeforeStep,
                                                    ShotStyle style, double fovDegrees, ShotPolicy policy,
                                                    BuildEnvelope envelope) {
        return splitWhatCannotBeSeen(scenes, builtBeforeStep, style, fovDegrees, policy, envelope,
                ShotPlanner.DEFAULT_ASPECT);
    }

    /**
     * То же, но разведка проверяет ещё и посадку в кадр целевого соотношения сторон.
     *
     * <p>«Не снять одним кадром» — это не только заслоны. Сцена шириной в шесть блоков в
     * тесной комнате физически не влезает в вертикальный кадр ни с какой точки: отойти
     * дальше стены нельзя. Раньше такая сцена оставалась одним кадром, поиск честно выбирал
     * «видно всё, но половина за краем» — и работа шла за кадром. Правило то же, что и с
     * видимостью: <b>не влезает с одной точки — это несколько кадров</b>.
     *
     * @param aspect соотношение сторон итогового видео (9/16 для шортса) — от него напрямую
     *               зависит, что считается «влезает»
     */
    public static List<Scene> splitWhatCannotBeSeen(List<Scene> scenes, List<Set<Pos>> builtBeforeStep,
                                                    ShotStyle style, double fovDegrees, ShotPolicy policy,
                                                    BuildEnvelope envelope, double aspect) {
        List<Scene> result = new ArrayList<>(scenes.size());
        for (Scene scene : scenes) {
            boolean interior = envelope != null
                    && !visibleFromOutside(scene, builtBeforeStep, style, fovDegrees, envelope);
            splitInto(result, scene, builtBeforeStep, style, fovDegrees, policy, 0,
                    interior ? null : envelope, aspect, interior);
        }
        return result;
    }

    /**
     * Ниже этой достижимой снаружи видимости сцена считается интерьером: она не «плохо
     * видна», её снаружи не существует — со всех сторон уже готовые стены.
     */
    private static final double INTERIOR_VISIBILITY = 0.3;

    /**
     * Видна ли сцена хоть с какой-то стороны, если стоять снаружи габарита постройки.
     *
     * <p>{@code false} — интерьер: единственный способ снять такую сцену — камерой внутри
     * помещения, и ограничивать её габаритом нельзя.
     */
    public static boolean visibleFromOutside(Scene scene, List<Set<Pos>> builtBeforeStep,
                                             ShotStyle style, double fovDegrees, BuildEnvelope envelope) {
        return bestVisibility(scene, builtBeforeStep, style, fovDegrees, envelope) >= INTERIOR_VISIBILITY;
    }

    /**
     * Ниже этой доли блоков в кадре сцена считается не влезающей и режется. Мягче, чем
     * «все до единого»: пара блоков у самой рамки — повод целиться точнее, а не резать.
     */
    private static final double SPLIT_FRAMING = 0.9;

    /**
     * Лестница сближения для разведки в тесноте — та же идея, что {@code DISTANCE_TRIES}
     * настоящего поиска: интерьер, который снаружи не виден, снимается с подхода вплотную.
     */
    private static final double[] PROBE_DISTANCES = {1.0, 0.72, 0.5, 0.34};

    private static void splitInto(List<Scene> out, Scene scene, List<Set<Pos>> builtBeforeStep,
                                  ShotStyle style, double fovDegrees, ShotPolicy policy, int depth,
                                  BuildEnvelope envelope, double aspect, boolean interior) {
        // Минимум на резку у интерьера вдвое ниже обычного. Страж minSceneBlocks*2 защищает
        // внешние сцены — крохотной фазе снаружи некуда поставить камеру. Интерьер снимается
        // изнутри и крупно: в тесной комнате в вертикальный кадр влезает всего несколько
        // блоков, и сцена, которую нельзя дорезать, гарантированно уводит работу за край.
        int minBlocks = interior ? policy.minSceneBlocks() : policy.minSceneBlocks() * 2;
        boolean divisible = depth < MAX_SPLIT_DEPTH
                && scene.steps().size() >= 2
                && scene.blocks().size() >= minBlocks;
        if (!divisible || oneShotQuality(scene, builtBeforeStep, style, fovDegrees, envelope, aspect) >= 1.0) {
            out.add(scene);
            return;
        }
        int total = scene.steps().size();
        int middle = total / 2;
        Scene first = sub(scene, 0, middle, total);
        Scene second = sub(scene, middle, total, total);
        splitInto(out, first, builtBeforeStep, style, fovDegrees, policy, depth + 1, envelope, aspect, interior);
        splitInto(out, second, builtBeforeStep, style, fovDegrees, policy, depth + 1, envelope, aspect, interior);
    }

    /**
     * Годится ли сцена под один кадр: и видно ({@link #SPLIT_VISIBILITY}), и влезает
     * ({@link #SPLIT_FRAMING}) хотя бы с одной точки. Возвращает лучший по кругу минимум
     * двух отношений «достигнуто/порог»; единица и выше — резать не надо.
     *
     * <p>Обе проверки грубые (редкая сетка направлений, типовая высота): они решают только
     * «резать ли», точку потом ищет полный поиск.
     */
    private static double oneShotQuality(Scene scene, List<Set<Pos>> builtBeforeStep,
                                         ShotStyle style, double fovDegrees, BuildEnvelope envelope,
                                         double aspect) {
        List<ShotPlanner.VisibilityCheck> checks = timeSlices(scene, builtBeforeStep);
        if (checks.isEmpty()) {
            return 1;
        }
        double baseDistance = CameraFraming.distanceFor(scene.radius(), fovDegrees, 1.0);
        double elevation = (style.minElevation() + style.maxElevation()) / 2;

        double best = 0;
        for (int a = 0; a < PROBE_AZIMUTHS; a++) {
            double azimuth = a * 360.0 / PROBE_AZIMUTHS;
            for (double closer : PROBE_DISTANCES) {
                // Разведка стоит там же, где встанет настоящая камера: снаружи габарита;
                // сближение по лестнице имеет смысл только в интерьере, снаружи её съедает кламп.
                double distance = baseDistance * closer;
                if (envelope != null) {
                    distance = Math.max(distance, envelope.exitDistance(scene.center(), azimuth, elevation));
                }
                double[] camera = CameraFraming.positionAround(scene.center(), distance, azimuth, elevation);
                double worst = 1;
                for (ShotPlanner.VisibilityCheck check : checks) {
                    worst = Math.min(worst,
                            Occlusion.visibleFraction(camera, check.targets(), check.occluders()));
                }
                double framed = ShotPlanner.framedShare(scene.blocks(), azimuth, elevation,
                        distance, fovDegrees, aspect, 1.0);
                best = Math.max(best, Math.min(worst / SPLIT_VISIBILITY, framed / SPLIT_FRAMING));
                if (best >= 1.0) {
                    return best;
                }
            }
        }
        return best;
    }

    /** Лучшая достижимая видимость сцены — для {@link #visibleFromOutside}. */
    private static double bestVisibility(Scene scene, List<Set<Pos>> builtBeforeStep,
                                         ShotStyle style, double fovDegrees, BuildEnvelope envelope) {
        List<ShotPlanner.VisibilityCheck> checks = timeSlices(scene, builtBeforeStep);
        if (checks.isEmpty()) {
            return 1;
        }
        double baseDistance = CameraFraming.distanceFor(scene.radius(), fovDegrees, 1.0);
        double elevation = (style.minElevation() + style.maxElevation()) / 2;

        double best = 0;
        for (int a = 0; a < PROBE_AZIMUTHS; a++) {
            double azimuth = a * 360.0 / PROBE_AZIMUTHS;
            double distance = envelope == null ? baseDistance
                    : Math.max(baseDistance, envelope.exitDistance(scene.center(), azimuth, elevation));
            double[] camera = CameraFraming.positionAround(scene.center(), distance, azimuth, elevation);
            double worst = 1;
            for (ShotPlanner.VisibilityCheck check : checks) {
                worst = Math.min(worst,
                        Occlusion.visibleFraction(camera, check.targets(), check.occluders()));
            }
            best = Math.max(best, worst);
            if (best >= SPLIT_VISIBILITY) {
                return best;
            }
        }
        return best;
    }

    private static Scene sub(Scene scene, int fromStep, int toStep, int totalSteps) {
        List<List<Pos>> steps = new ArrayList<>(scene.steps().subList(fromStep, toStep));
        List<Pos> blocks = new ArrayList<>();
        for (List<Pos> step : steps) {
            blocks.addAll(step);
        }
        return piece(steps, blocks, scene, fromStep, toStep, totalSteps);
    }

    private static List<Scene> sliceIfTooBig(Scene scene, ShotPolicy policy) {
        if (scene.radius() <= policy.maxSceneRadius() || scene.steps().size() <= 1) {
            return List.of(scene);
        }

        List<Scene> pieces = new ArrayList<>();
        List<List<Pos>> steps = scene.steps();
        int totalSteps = steps.size();

        List<List<Pos>> pieceSteps = new ArrayList<>();
        List<Pos> pieceBlocks = new ArrayList<>();
        int pieceStart = 0;

        for (int i = 0; i < totalSteps; i++) {
            List<Pos> step = steps.get(i);
            if (!pieceBlocks.isEmpty()) {
                List<Pos> trial = new ArrayList<>(pieceBlocks);
                trial.addAll(step);
                double[] centre = ShotPlanner.centerOf(trial);
                double radius = ShotPlanner.radiusOf(trial, centre);
                if (radius > policy.maxSceneRadius() && pieceBlocks.size() >= policy.minSceneBlocks()) {
                    pieces.add(piece(pieceSteps, pieceBlocks, scene, pieceStart, i, totalSteps));
                    pieceSteps = new ArrayList<>();
                    pieceBlocks = new ArrayList<>();
                    pieceStart = i;
                }
            }
            pieceSteps.add(step);
            pieceBlocks.addAll(step);
        }
        if (!pieceBlocks.isEmpty()) {
            pieces.add(piece(pieceSteps, pieceBlocks, scene, pieceStart, totalSteps, totalSteps));
        }
        return pieces;
    }

    private static Scene piece(List<List<Pos>> steps, List<Pos> blocks, Scene original,
                               int fromStep, int toStep, int totalSteps) {
        double[] centre = ShotPlanner.centerOf(blocks);
        double radius = ShotPlanner.radiusOf(blocks, centre);
        int duration = original.endTick() - original.startTick();
        int startTick = original.startTick() + (int) ((long) fromStep * duration / Math.max(1, totalSteps));
        int endTick = original.startTick() + (int) ((long) toStep * duration / Math.max(1, totalSteps));
        // Кусок режется по радиусу внутри одного и того же участка — форма и направление
        // от этого не меняются, это всё ещё тот же кусок постройки, просто снят не разом.
        return new Scene(List.copyOf(blocks), List.copyOf(steps), centre, radius,
                original.shape(), original.direction(), startTick, endTick,
                original.firstGlobalStep() + fromStep);
    }

    /** Копит соседние участки в одну сцену, пока направление, форма и радиус позволяют. */
    private static final class Accumulator {
        BuildAnalyzer.Shape shape;
        double direction = Double.NaN;
        int firstGlobalStep;
        int blockCount;
        int startTick;
        int endTick;
        final List<Pos> blocks = new ArrayList<>();
        final List<List<Pos>> steps = new ArrayList<>();

        boolean isEmpty() {
            return blocks.isEmpty();
        }

        void start(BuildAnalyzer.WorkSegment segment) {
            shape = segment.shape();
            direction = segment.direction();
            firstGlobalStep = segment.startStep();
            startTick = segment.startTick();
            endTick = segment.endTick();
            blocks.addAll(segment.blocks());
            steps.addAll(segment.steps());
            blockCount = blocks.size();
        }

        void add(BuildAnalyzer.WorkSegment segment) {
            blocks.addAll(segment.blocks());
            steps.addAll(segment.steps());
            blockCount = blocks.size();
            endTick = segment.endTick();
            if (!Double.isNaN(segment.direction())) {
                direction = Double.isNaN(direction) ? segment.direction()
                        : direction + ShotPlanner.shortestTurn(segment.direction() - direction) * 0.5;
            }
        }

        Scene finish() {
            double[] centre = ShotPlanner.centerOf(blocks);
            double radius = ShotPlanner.radiusOf(blocks, centre);
            return new Scene(List.copyOf(blocks), List.copyOf(steps), centre, radius,
                    shape, direction, startTick, endTick, firstGlobalStep);
        }
    }
}

package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Подбор ракурса на слой.
 *
 * <p>Видимость — условие необходимое, но не достаточное. Раньше побеждал первый кандидат
 * с лучшей видимостью, а поскольку при пустом дворе видно отовсюду, лучшим всегда
 * оказывался первый по счёту: камера каждый раз вставала с одной и той же стороны и на
 * нижней границе допустимой высоты. Планы выходили одинаковые и случайные.
 *
 * <p>Теперь при равной видимости решает композиция, и правила взяты из съёмочной практики:
 *
 * <ul>
 *   <li><b>Три четверти вместо фронта.</b> Строго перпендикулярно стене постройка выглядит
 *       плоско; угол около сорока пяти градусов к грани показывает две стороны сразу;</li>
 *   <li><b>Высота из середины диапазона</b>, а не с самого низа — иначе все планы
 *       оказываются на одной линии;</li>
 *   <li><b>Разворот между соседними планами не меньше тридцати градусов.</b> Меньше — и
 *       склейка читается как рывок, а не как смена плана;</li>
 *   <li><b>Не перепрыгивать через ось.</b> План с противоположной стороны переворачивает
 *       направление движения в кадре;</li>
 *   <li><b>Правило третей.</b> Целимся не в самый центр, а чуть выше — постройка садится
 *       в нижние две трети кадра, как это и делают в архитектурной съёмке.</li>
 * </ul>
 */
public final class ShotPlanner {

    private static final int AZIMUTH_STEPS = 24;
    private static final int ELEVATION_STEPS = 5;
    private static final int MAX_SAMPLES = 160;

    /**
     * Вокруг якорных азимутов (перпендикуляров к фронту постройки) добавляем эту россыпь
     * кандидатов поверх обычного круга — чтобы там, где якорь и так побеждает, у него было
     * из чего выбрать поточнее. Сам круг при этом никуда не девается: якорь — это слагаемое
     * в оценке, а не ограничение перебора, иначе поиск не сможет уйти от столба, который
     * случайно оказался как раз в направлении якоря (было и оказалось реальным регрессом
     * видимости — см. историю).
     */
    private static final double ANCHOR_WINDOW = 50.0;
    private static final int ANCHOR_SAMPLES = 7;
    /**
     * Вес направления на фронт постройки. Подбирать его как противовес видимости на глаз не
     * получилось: любое фиксированное число либо давало реальный регресс видимости там, где
     * в сторону якоря случайно смотрел столб, либо было слишком слабым, чтобы всерьёз
     * развернуть камеру к фронту. Поэтому бонус включается только при {@link #FACING_MIN_VISIBILITY}
     * и выше — среди кандидатов, которые и так хорошо видно, решает направление; там, где видно
     * плохо, направление вообще не участвует в споре, и решает чистая видимость.
     */
    private static final double FACING_WEIGHT = 2.5;
    /** За этим углом от ближайшего якоря направление на фронт уже ничего не значит. */
    private static final double FACING_FALLOFF = 90.0;
    /** Ниже этой видимости бонус за направление не действует — см. {@link #FACING_WEIGHT}. */
    private static final double FACING_MIN_VISIBILITY = 0.75;

    /** Видимость важнее композиции, поэтому её вес на порядок больше. */
    private static final double VISIBILITY_WEIGHT = 10.0;

    /**
     * Попадание в кадр — такое же жёсткое требование, как видимость, и вес у него того же
     * порядка.
     *
     * <p>Без этого члена посадка в кадр не значила ничего: {@link #DISTANCE_TRIES} подъезжает
     * ближе ради видимости, и кандидат, обрезающий половину снимаемого, спокойно выигрывал —
     * видимость у него считалась лучше, а за обрезку никто не штрафовал. Блок, который ставят
     * прямо сейчас, а его не видно за краем экрана, — такой же брак, как блок за стеной.
     */
    private static final double FRAMING_WEIGHT = 9.0;
    /** Ближе этого угла к предыдущему плану ставить нельзя — склейка читается рывком. */
    private static final double MIN_TURN = 30;
    /** Дальше этого — перескок через ось, движение в кадре перевернётся. */
    private static final double MAX_TURN = 150;
    /** Ближе этого к уже занятому ракурсу другая дорожка вставать не должна. */
    private static final double MIN_SPREAD = 55;

    /**
     * Больше этого на один кадр поворачивать нельзя: сплайн уведёт камеру мимо цели.
     *
     * <p>Запас нужен потому, что к этому повороту добавляется смещение прицела вслед за
     * работой — суммарный угол между кадрами всегда больше заданной дуги.
     */
    private static final double MAX_ARC_PER_STEP = 30;

    /**
     * Во сколько раз пробуем подойти ближе, если снаружи слой не виден.
     *
     * <p>Интерьер, обнесённый уже готовыми стенами, снаружи не виден ни с какой стороны.
     * Единственный выход — подойти ближе, вплоть до того, чтобы оказаться внутри. Идём от
     * задуманной крупности к самой тесной и берём ближайшую только ради видимости.
     */
    private static final double[] DISTANCE_TRIES = {1.0, 0.72, 0.5, 0.34, 0.22};
    /** Штраф за каждый шаг сближения: задуманную крупность бросаем неохотно. */
    private static final double CLOSER_PENALTY = 0.35;

    /**
     * Соотношение сторон по умолчанию — горизонтальное видео. Съёмка под шортс передаёт
     * своё: вертикальный кадр почти вдвое уже, и посадка в него совсем другая.
     */
    public static final double DEFAULT_ASPECT = 16.0 / 9.0;

    /**
     * Правило третей: целимся выше геометрического центра на эту долю радиуса, чтобы
     * постройка села в нижние две трети кадра, а не делила его пополам.
     */
    private static final double AIM_LIFT = 0.18;

    /**
     * Во сколько раз максимум разрешено отойти ради того, чтобы всё влезло в кадр.
     *
     * <p>У сцены, которая окружает камеру (интерьер по кольцу вдоль стен), «влезло всё» не
     * достигается ни на каком расстоянии — не будь потолка, камера уезжала бы в бесконечность
     * вместо того, чтобы честно снять то, что снять можно.
     */
    private static final double MAX_FIT_GROWTH = 2.5;

    /** На сколько градусов поднимаем камеру над плоским слоем. */
    private static final double FLAT_LIFT = 25;
    /** Выше этого не поднимаемся ни при какой форме: дальше начинается мёртвый вид сверху. */
    private static final double ELEVATION_CEILING = 62;

    private ShotPlanner() {
    }

    /**
     * Один срез проверки видимости: что снимаем и что к этому моменту уже стоит.
     *
     * <p>Проверять слой одним куском нельзя: блоки, поставленные раньше внутри того же
     * слоя, к концу заслоняют фронт не хуже соседних слоёв. Поэтому направление
     * оценивается по нескольким моментам сразу.
     */
    public record VisibilityCheck(Collection<Pos> targets, Set<Pos> occluders) {
    }

    /** Ракурс вместе с параметрами, из которых он получен: нужны движению и следующему плану. */
    public record Placement(CameraShot shot, double azimuth, double elevation, double distance) {
    }

    /**
     * Лучший ракурс на набор блоков.
     *
     * @param previousAzimuth азимут предыдущего плана этой же дорожки, либо {@code NaN}
     */
    public static Placement plan(Collection<Pos> targets, Set<Pos> occluders,
                                 ShotStyle style, double fovDegrees, int tick,
                                 double previousAzimuth) {
        return plan(targets, List.of(new VisibilityCheck(targets, occluders)),
                style, fovDegrees, tick, previousAzimuth);
    }

    /**
     * Лучший ракурс на слой.
     *
     * @param checks моменты, в которые проверяется видимость: направление должно годиться
     *               и в начале слоя, и в конце, когда половина работы уже заслоняет фронт
     * @param previousAzimuth азимут предыдущего плана этой же дорожки, либо {@code NaN}
     */
    public static Placement plan(Collection<Pos> targets, List<VisibilityCheck> checks,
                                 ShotStyle style, double fovDegrees, int tick,
                                 double previousAzimuth) {
        return plan(targets, checks, style, fovDegrees, tick, previousAzimuth, List.of());
    }

    /**
     * То же, но с оглядкой на ракурсы, уже занятые другими дорожками этого слоя.
     *
     * @param avoid азимуты, от которых надо отойти: иначе все дорожки с одинаковыми
     *              правилами композиции выберут один и тот же ракурс
     */
    public static Placement plan(Collection<Pos> targets, List<VisibilityCheck> checks,
                                 ShotStyle style, double fovDegrees, int tick,
                                 double previousAzimuth, List<Double> avoid) {
        return plan(targets, checks, style, fovDegrees, tick, previousAzimuth, avoid, List.of());
    }

    /**
     * То же, но с якорями по направлению фронта постройки — азимуты рядом с якорем получают
     * бонус к оценке, как и «три четверти» или разворот от предыдущего плана.
     *
     * <p>Это именно бонус, а не ограничение перебора: круг всё равно проверяется целиком.
     * Первая версия резала поиск до узких окон вокруг якорей и на реальных данных дала
     * настоящий регресс видимости — ровно там, где в направлении якоря случайно стоял столб,
     * а с другой стороны было чисто, поиск больше не мог туда уйти. Бонус слабее видимости
     * (см. {@link #FACING_WEIGHT} против {@link #VISIBILITY_WEIGHT}), поэтому решает спор
     * только между визуально близкими кандидатами, а не пересиливает разницу в видимости.
     *
     * <p>Обычно сюда приходят два якоря — по перпендикуляру в каждую сторону от направления
     * фронта: снаружи для внешней стены, изнутри для того же кольца, но с другой стороны — и
     * то, какой из них в итоге победит, решает та же проверка видимости, что и всегда, а не
     * жёсткое правило «снаружи/изнутри».
     *
     * @param azimuthAnchors направления, которым отдаётся предпочтение; пустой список — как
     *                       если бы якорей не было вовсе (поведение не меняется)
     */
    public static Placement plan(Collection<Pos> targets, List<VisibilityCheck> checks,
                                 ShotStyle style, double fovDegrees, int tick,
                                 double previousAzimuth, List<Double> avoid,
                                 List<Double> azimuthAnchors) {
        return plan(targets, checks, style, fovDegrees, tick, previousAzimuth, avoid,
                azimuthAnchors, DEFAULT_ASPECT);
    }

    /**
     * То же, но под конкретное соотношение сторон итогового видео.
     *
     * @param aspect ширина, делённая на высоту: 16/9 для горизонтального, 9/16 для шортса.
     *               Вертикальный кадр почти вдвое уже, и то, что спокойно помещалось в
     *               горизонтальный, в шортсе оказывается за краем — поэтому дистанция
     *               считается точной посадкой в кадр, а не вписыванием шара по одному
     *               вертикальному углу обзора.
     */
    public static Placement plan(Collection<Pos> targets, List<VisibilityCheck> checks,
                                 ShotStyle style, double fovDegrees, int tick,
                                 double previousAzimuth, List<Double> avoid,
                                 List<Double> azimuthAnchors, double aspect) {
        return plan(targets, checks, style, fovDegrees, tick, previousAzimuth, avoid,
                azimuthAnchors, aspect, null);
    }

    /**
     * То же, но камера обязана стоять снаружи габарита постройки.
     *
     * <p>Это смена параметризации, а не ещё один вес в оценке. Камера по-прежнему ищется
     * на луче «от прицела наружу», но дистанция вдоль луча снизу ограничена выходом из
     * габарита: кандидатов внутри дома просто не существует, и видимости больше не с чем
     * спорить. Раньше она честно загоняла камеру внутрь — изнутри кольцо стен видно со
     * всех сторон разом, а вес видимости самый большой; никакой контр-вес это стабильно
     * не перевешивал (см. историю: любое фиксированное число либо давало регресс видимости,
     * либо не разворачивало камеру).
     *
     * @param keepOutsideOf габарит, внутри которого камере стоять нельзя; {@code null} —
     *                      без ограничения (интерьер снимается изнутри, ему сюда передают
     *                      именно {@code null}, а не огибающую комнаты)
     */
    public static Placement plan(Collection<Pos> targets, List<VisibilityCheck> checks,
                                 ShotStyle style, double fovDegrees, int tick,
                                 double previousAzimuth, List<Double> avoid,
                                 List<Double> azimuthAnchors, double aspect,
                                 BuildEnvelope keepOutsideOf) {
        double[] center = centerOf(targets);
        double radius = radiusOf(targets, center);
        double baseDistance = CameraFraming.distanceFor(radius, fovDegrees, 1.0);
        double aimOffsetY = radius * AIM_LIFT;
        double safeZone = style.safeZone();

        List<VisibilityCheck> sampled = new ArrayList<>();
        for (VisibilityCheck check : checks) {
            if (!check.targets().isEmpty()) {
                sampled.add(new VisibilityCheck(sample(check.targets()), check.occluders()));
            }
        }
        List<double[]> framePoints = pointsOf(sample(targets));

        double lift = FLAT_LIFT * flatness(targets);
        double minElevation = Math.min(style.minElevation() + lift, ELEVATION_CEILING);
        double maxElevation = Math.min(style.maxElevation() + lift, ELEVATION_CEILING);

        double bestScore = Double.NEGATIVE_INFINITY;
        double bestAzimuth = 45;
        double bestElevation = (minElevation + maxElevation) / 2;
        double bestDistance = baseDistance;

        List<Double> azimuths = azimuthCandidates(azimuthAnchors);
        for (double azimuth : azimuths) {
            for (int e = 0; e < ELEVATION_STEPS; e++) {
                double elevation = minElevation
                        + e * (maxElevation - minElevation) / (ELEVATION_STEPS - 1);

                // Своя дистанция на каждое направление, а не одна на всю сцену: длинная стена
                // с торца и она же анфас требуют совершенно разного отхода, и вписывание шара
                // этой разницы не видело в принципе.
                double fitted = CameraFraming.distanceToFit(framePoints, center, aimOffsetY,
                        azimuth, elevation, fovDegrees, aspect, safeZone, baseDistance, MAX_FIT_GROWTH);

                // Порог выхода из габарита — снизу под все пробы дистанции этого направления:
                // сближение ради видимости упирается в стену дома снаружи, а не проходит её.
                double exit = keepOutsideOf == null ? 0
                        : keepOutsideOf.exitDistance(center, azimuth, elevation);

                for (int d = 0; d < DISTANCE_TRIES.length; d++) {
                    double tryDistance = Math.max(fitted * DISTANCE_TRIES[d], exit);
                    double closerPenalty = CLOSER_PENALTY * d;

                    double visibility = visibilityAcross(center, tryDistance, azimuth, elevation,
                            sampled, style);
                    double framed = framedFraction(framePoints, center, aimOffsetY, tryDistance,
                            azimuth, elevation, fovDegrees, aspect, safeZone);
                    double facing = visibility >= FACING_MIN_VISIBILITY
                            ? facingScore(azimuth, azimuthAnchors) : 0;
                    double score = visibility * VISIBILITY_WEIGHT
                            + framed * FRAMING_WEIGHT
                            + threeQuarterScore(azimuth)
                            + elevationScore(elevation, minElevation, maxElevation)
                            + turnScore(azimuth, previousAzimuth)
                            + spreadScore(azimuth, avoid)
                            + FACING_WEIGHT * facing
                            - closerPenalty;

                    if (score > bestScore) {
                        bestScore = score;
                        bestAzimuth = azimuth;
                        bestElevation = elevation;
                        bestDistance = tryDistance;
                    }
                }
            }
        }
        double distance = bestDistance;
        // Если вообще все кандидаты оказались внутри чего-то (тесная комната со всех сторон
        // в стенах — там DISTANCE_TRIES жмёт камеру внутрь, и деться в переборе действительно
        // некуда), победителя выбрали по остальным критериям, а он всё ещё может быть внутри
        // блока. Тут — последняя, гарантированная проверка именно того кандидата, который
        // реально станет кадром.
        distance = clearOfEmbedding(center, distance, bestAzimuth, bestElevation, checks);
        return place(center, radius, distance, bestAzimuth, bestElevation, tick);
    }

    /** Отодвигает камеру назад, пока её точка не выйдет из occluders/targets всех проверок. */
    private static double clearOfEmbedding(double[] center, double distance, double azimuth,
                                           double elevation, List<VisibilityCheck> checks) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double[] position = CameraFraming.positionAround(center, distance, azimuth, elevation);
            Pos cell = cellOf(position);
            boolean embedded = false;
            for (VisibilityCheck check : checks) {
                if (check.occluders().contains(cell) || check.targets().contains(cell)) {
                    embedded = true;
                    break;
                }
            }
            if (!embedded) {
                return distance;
            }
            distance *= 1.15;
        }
        return distance;
    }

    /**
     * Худшая видимость по всем проверяемым моментам.
     *
     * <p>Именно худшая, а не средняя: направление, из которого в конце слоя не видно
     * ничего, не спасает то, что в начале было видно всё.
     */
    private static double visibilityAcross(double[] center, double distance, double azimuth,
                                           double elevation, List<VisibilityCheck> checks,
                                           ShotStyle style) {
        if (checks.isEmpty()) {
            return 1;
        }
        double blend = style.contextBlend();
        double worst = 1;
        for (VisibilityCheck check : checks) {
            // Камеру ставим ровно туда, где она окажется в этот момент — по тому же правилу,
            // по которому потом раскладываются кадры (см. followShots): статичная доктрина
            // держится центра всей сцены, ведущая едет за работой. Раньше здесь было то одно,
            // то другое для всех подряд, и поиск оценивал видимость из точки, откуда съёмки
            // не будет: проверяли не то, что снимаем.
            double[] aim = centerOf(check.targets());
            double[] evalCenter = {
                    lerp(aim[0], center[0], blend),
                    lerp(aim[1], center[1], blend),
                    lerp(aim[2], center[2], blend)
            };
            double[] camera = CameraFraming.positionAround(evalCenter, distance, azimuth, elevation);
            // Точка камеры не должна оказаться внутри уже стоящего или прямо сейчас
            // кладущегося блока — иначе она в него же и упрётся, что бы ни показал луч
            // до цели. Раньше это никто не проверял, и на дожиме DISTANCE_TRIES камеру
            // могло занести внутрь стены вместо того, чтобы просто зайти внутрь помещения.
            if (isEmbedded(camera, check)) {
                return 0;
            }
            worst = Math.min(worst, Occlusion.visibleFraction(camera, check.targets(), check.occluders()));
        }
        return worst;
    }

    /** Клетка камеры совпала с уже стоящим или снимаемым блоком. */
    private static boolean isEmbedded(double[] camera, VisibilityCheck check) {
        Pos cell = cellOf(camera);
        return check.occluders().contains(cell) || check.targets().contains(cell);
    }

    private static Pos cellOf(double[] point) {
        return new Pos((int) Math.floor(point[0]), (int) Math.floor(point[1]), (int) Math.floor(point[2]));
    }

    /**
     * Ставит камеру на заданный азимут без поиска — ракурс уже известен (например, идёт от
     * фактического положения фронта постройки), перебирать варианты незачем.
     *
     * <p>Высоту и дистанцию сюда приносят готовыми, обычно от {@link #plan} на весь слой:
     * они не зависят от того, с какой стороны сейчас смотрим, только от его формы и размера.
     */
    public static Placement placeAt(Collection<Pos> targets, double azimuth, double elevation,
                                    double distance, int tick, Set<Pos> solid) {
        double[] center = centerOf(targets);
        double radius = radiusOf(targets, center);
        // Ракурс тут не подбирается поиском, значит и отбраковки заведомо плохих кандидатов
        // не было — без этой проверки камера точно так же может оказаться внутри блока,
        // как раньше могла у ведущих кадров (см. clearOfBlocks в followShots).
        double clearDistance = clearOfBlocks(center, distance, azimuth, elevation, solid);
        return place(center, radius, clearDistance, azimuth, elevation, tick);
    }

    /**
     * Кадры на весь слой: камера ведёт работу.
     *
     * <p>Направление на слой выбирается <b>один раз</b> — по нему и работают все правила
     * композиции. Дальше камера просто переезжает так, чтобы текущий фронт держался в
     * центре кадра: если менять ещё и направление, план превратится в рыскание.
     *
     * <p>Прицел смешивается с центром слоя по {@link ShotStyle#contextBlend()}: у ближних
     * доктрин — точно в работу, у дальних — с оглядкой на слой, чтобы он не выезжал за края.
     *
     * @param front замеры движения фронта; пустой список означает «слой снять одним кадром»
     */
    public static List<CameraShot> followShots(Collection<Pos> targets, Placement start,
                                               ShotStyle style, List<BuildTimeline.FrontSample> front,
                                               int endTick) {
        return followShots(targets, start, style, front, endTick, Set.of());
    }

    /**
     * То же, но с учётом того, что уже стоит в мире: расстояние здесь пересчитывается на
     * каждый кадр под ширину фронта, и ничто раньше не мешало камере уехать точкой внутрь
     * блока, который сама же снимает. Теперь это проверяется, и камера отодвигается назад.
     *
     * @param occluders блоки, внутрь которых камере заходить нельзя — прошлые слои и сам
     *                  снимаемый слой целиком (даже то, что ещё не поставлено к этому кадру:
     *                  безопаснее держаться подальше и от будущих блоков тоже)
     */
    public static List<CameraShot> followShots(Collection<Pos> targets, Placement start,
                                               ShotStyle style, List<BuildTimeline.FrontSample> front,
                                               int endTick, Set<Pos> occluders) {
        return followShots(targets, start, style, front, endTick, occluders, null);
    }

    /**
     * То же, но камера всю дорогу держится снаружи габарита постройки.
     *
     * <p>Стартовый ракурс снаружи ({@link #plan} с тем же габаритом) ещё не значит, что
     * снаружи вся раскладка: у ведущих доктрин прицел едет за фронтом и дистанция
     * пересчитывается на каждый кадр — без собственного порога кадры в середине сцены
     * спокойно оказывались внутри дома.
     *
     * @param keepOutsideOf габарит, внутри которого камере стоять нельзя; {@code null} —
     *                      без ограничения (интерьер)
     */
    public static List<CameraShot> followShots(Collection<Pos> targets, Placement start,
                                               ShotStyle style, List<BuildTimeline.FrontSample> front,
                                               int endTick, Set<Pos> occluders,
                                               BuildEnvelope keepOutsideOf) {
        Set<Pos> solid = new HashSet<>(occluders);
        solid.addAll(targets);

        List<CameraShot> shots = new ArrayList<>();
        if (front.isEmpty() || !style.follows()) {
            shots.add(start.shot());
            if (style.moving() && endTick > start.shot().tick()) {
                double[] center = centerOf(targets);
                double azimuth = start.azimuth() + style.arcDegrees();
                double distance = start.distance() * style.distanceRatio();
                if (keepOutsideOf != null) {
                    distance = Math.max(distance,
                            keepOutsideOf.exitDistance(center, azimuth, start.elevation()));
                }
                distance = clearOfBlocks(center, distance, azimuth, start.elevation(), solid);
                shots.add(place(center, radiusOf(targets, center),
                        distance, azimuth, start.elevation(), endTick).shot());
            }
            return shots;
        }

        double[] layerCenter = centerOf(targets);
        double layerRadius = radiusOf(targets, layerCenter);
        double blend = style.contextBlend();
        int total = front.size();

        double[] prevAim = null;
        double prevDistance = 0, prevAzimuth = 0;

        for (int i = 0; i < total; i++) {
            BuildTimeline.FrontSample sample = front.get(i);
            double progress = total == 1 ? 0 : (double) i / (total - 1);

            double[] aim = {
                    lerp(sample.center()[0], layerCenter[0], blend),
                    lerp(sample.center()[1], layerCenter[1], blend),
                    lerp(sample.center()[2], layerCenter[2], blend)
            };
            double radius = style.frameOnFront()
                    ? Math.max(sample.radius(), 1.5)
                    : layerRadius;
            double distance = start.distance() / Math.max(1.0e-6, radiusRatio(style, layerRadius, radius))
                    * style.distanceRatio(progress);

            // Дугу ограничиваем плотностью кадров: между ними идёт сплайн, и если на
            // один кадр приходится больше сорока пяти градусов, он уводит камеру мимо
            // цели — со стороны это выглядит как «едет, но не смотрит на работу».
            double arc = Math.min(style.arcDegrees(), MAX_ARC_PER_STEP * Math.max(1, total - 1));
            double azimuth = start.azimuth() + arc * progress;
            if (keepOutsideOf != null) {
                distance = Math.max(distance,
                        keepOutsideOf.exitDistance(aim, azimuth, start.elevation()));
            }
            distance = clearOfBlocks(aim, distance, azimuth, start.elevation(), solid);

            // Проверенные концы отрезка ещё не значат безопасный сплайн между ними: Flashback
            // ведёт кадры Catmull-Rom-кривой, а она умеет выгибаться за пределы прямой между
            // двумя точками — особенно на повороте вокруг угла. Прямой середины отрезка
            // недостаточно, чтобы доказать, что кривая безопасна, но если даже она уже внутри
            // блока — кривая тем более где-то заденет, и плавный проезд подменяется резким
            // резом: это не так красиво, зато не сквозь стену.
            boolean cut = false;
            if (prevAim != null) {
                double[] midAim = {
                        (prevAim[0] + aim[0]) / 2, (prevAim[1] + aim[1]) / 2, (prevAim[2] + aim[2]) / 2
                };
                double midDistance = (prevDistance + distance) / 2;
                double midAzimuth = prevAzimuth + shortestTurn(azimuth - prevAzimuth) / 2;
                double[] midPosition = CameraFraming.positionAround(midAim, midDistance, midAzimuth, start.elevation());
                cut = solid.contains(cellOf(midPosition));
            }

            CameraShot shot = place(aim, radius, distance, azimuth, start.elevation(), sample.tick()).shot();
            shots.add(cut ? shot.withCut(true) : shot);

            prevAim = aim;
            prevDistance = distance;
            prevAzimuth = azimuth;
        }
        return shots;
    }

    /**
     * Отодвигает камеру назад по тому же лучу, пока её точка не выйдет из {@code solid}.
     *
     * <p>Геометрический подбор ракурса ({@link #plan}) сюда не заглядывает — он выбирает
     * направление один раз в начале слоя, а не на каждый кадр движения, поэтому кадрам
     * следом за фронтом нужна собственная проверка.
     */
    private static double clearOfBlocks(double[] aim, double distance, double azimuth,
                                        double elevation, Set<Pos> solid) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double[] position = CameraFraming.positionAround(aim, distance, azimuth, elevation);
            if (!solid.contains(cellOf(position))) {
                return distance;
            }
            distance *= 1.15;
        }
        return distance;
    }

    /** Во сколько раз отойти иначе, если кадрируем по фронту, а не по слою. */
    private static double radiusRatio(ShotStyle style, double layerRadius, double radius) {
        return style.frameOnFront() ? layerRadius / Math.max(1.0e-6, radius) : 1.0;
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static Placement place(double[] center, double radius, double distance,
                                   double azimuth, double elevation, int tick) {
        double[] position = CameraFraming.positionAround(center, distance, azimuth, elevation);
        // Правило третей: целимся выше середины, чтобы постройка села в нижние две трети
        // кадра, а не делила его пополам. Ровно тот же подъём прицела учитывает и посадка
        // в кадр (см. distanceToFit) — иначе она считала бы кадр, которого не будет.
        double[] aim = {center[0], center[1] + radius * AIM_LIFT, center[2]};
        float[] angles = CameraFraming.lookAt(position, aim);
        return new Placement(
                new CameraShot(tick, position[0], position[1], position[2], angles[0], angles[1], false),
                azimuth, elevation, distance);
    }

    /**
     * Азимуты-кандидаты для перебора: весь круг всегда (видимость должна иметь право выбрать
     * любую сторону, откуда столб не мешает), плюс — если есть якоря — россыпь точек с более
     * мелким шагом вокруг них, чтобы направление на фронт постройки могло победить точно, а
     * не с точностью до пятнадцати градусов обычной сетки.
     */
    private static List<Double> azimuthCandidates(List<Double> anchors) {
        List<Double> result = new ArrayList<>(AZIMUTH_STEPS);
        for (int a = 0; a < AZIMUTH_STEPS; a++) {
            result.add(a * 360.0 / AZIMUTH_STEPS);
        }
        if (anchors == null || anchors.isEmpty()) {
            return result;
        }
        for (double anchor : anchors) {
            for (int i = 0; i < ANCHOR_SAMPLES; i++) {
                double offset = -ANCHOR_WINDOW / 2 + i * ANCHOR_WINDOW / (ANCHOR_SAMPLES - 1);
                result.add(((anchor + offset) % 360 + 360) % 360);
            }
        }
        return result;
    }

    /**
     * Насколько ракурс близок к якорному направлению (перпендикуляру к фронту постройки).
     * Максимум прямо на якоре, линейно падает до нуля за {@link #FACING_FALLOFF} градусов —
     * дальше уже всё равно, какая разница, спорить тут не с чем.
     */
    private static double facingScore(double azimuth, List<Double> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return 0;
        }
        double best = 0;
        for (double anchor : anchors) {
            double turn = Math.abs(shortestTurn(azimuth - anchor));
            best = Math.max(best, Math.max(0, 1.0 - turn / FACING_FALLOFF));
        }
        return best;
    }

    /**
     * Насколько ракурс похож на три четверти: максимум на диагоналях, ноль — строго вдоль
     * оси, то есть в лоб стене.
     */
    static double threeQuarterScore(double azimuth) {
        double offset = Math.abs(((azimuth % 90) + 90) % 90 - 45);
        return 1.0 - offset / 45.0;
    }

    /**
     * Насколько ракурс свободен от уже занятых другими дорожками.
     *
     * <p>Штрафуем сближение меньше чем на {@link #MIN_SPREAD} градусов. Вес заметно
     * меньше видимости: развести планы полезно, но не ценой того, что снимать станет нечего.
     */
    static double spreadScore(double azimuth, List<Double> avoid) {
        double penalty = 0;
        for (double taken : avoid) {
            double turn = Math.abs(shortestTurn(azimuth - taken));
            if (turn < MIN_SPREAD) {
                penalty -= 2.5 * (1.0 - turn / MIN_SPREAD);
            }
        }
        return penalty;
    }

    /** Разворот от предыдущего плана: слишком малый — рывок, слишком большой — перескок через ось. */
    static double turnScore(double azimuth, double previousAzimuth) {
        if (Double.isNaN(previousAzimuth)) {
            return 0;
        }
        double turn = Math.abs(shortestTurn(azimuth - previousAzimuth));
        if (turn < MIN_TURN) {
            return -3.0 * (1.0 - turn / MIN_TURN);
        }
        if (turn > MAX_TURN) {
            return -2.0 * (turn - MAX_TURN) / (180 - MAX_TURN);
        }
        return 1.2;
    }

    /** Кратчайший разворот в градусах, от -180 до 180. */
    public static double shortestTurn(double degrees) {
        return ((degrees % 360) + 540) % 360 - 180;
    }

    /** Середина диапазона высот лучше краёв: у самых границ планы однообразны. */
    private static double elevationScore(double elevation, double minElevation, double maxElevation) {
        double middle = (minElevation + maxElevation) / 2;
        double half = Math.max(1.0e-6, (maxElevation - minElevation) / 2);
        return 0.6 * (1.0 - Math.abs(elevation - middle) / half);
    }

    /**
     * Насколько слой плоский: 1 — горизонтальный лист вроде пола, 0 — стена, кольцо стен
     * или объёмный кусок.
     *
     * <p>Одного габарита мало, и это было настоящей ошибкой: кольцо стен высотой в три
     * блока на стороне в двенадцать по габариту неотличимо от плиты, и камера над ним
     * поднималась как над полом. Поэтому к отношению высоты добавлен второй множитель —
     * <b>насколько плотно занят пол габарита</b>. У плиты он около единицы, у кольца стен
     * около четверти, и подъём для кольца почти исчезает.
     */
    public static double flatness(Collection<Pos> blocks) {
        if (blocks.isEmpty()) {
            return 0;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Long> footprint = new HashSet<>();
        for (Pos pos : blocks) {
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
            footprint.add(((long) pos.x() << 32) ^ (pos.z() & 0xFFFFFFFFL));
        }
        double vertical = maxY - minY + 1;
        double horizontal = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        double area = (double) (maxX - minX + 1) * (maxZ - minZ + 1);

        double lowness = Math.max(0.0, Math.min(1.0, 1.0 - vertical / Math.max(1.0, horizontal) * 2.0));
        double fill = area <= 0 ? 0 : Math.min(1.0, footprint.size() / area);
        return lowness * fill;
    }

    public static double[] centerOf(Collection<Pos> blocks) {
        if (blocks.isEmpty()) {
            return new double[]{0, 0, 0};
        }
        double x = 0, y = 0, z = 0;
        for (Pos pos : blocks) {
            x += pos.x() + 0.5;
            y += pos.y() + 0.5;
            z += pos.z() + 0.5;
        }
        return new double[]{x / blocks.size(), y / blocks.size(), z / blocks.size()};
    }

    public static double radiusOf(Collection<Pos> blocks, double[] center) {
        // Пол блока — заведомо плохой пол: на крохотной кучке блоков (обрезок одного
        // столбика) дистанция считается от радиуса, и с самым близким DISTANCE_TRIES
        // могла схлопнуться меньше блока — снаружи в упор ставить камеру уже некуда,
        // и поиск подбирал «наименее плохой» вариант прямо внутри геометрии. 2.5 —
        // это уже кадр с запасом, показывающий предмет не одной его текстурой на весь экран.
        double max = 2.5;
        for (Pos pos : blocks) {
            double dx = pos.x() + 0.5 - center[0];
            double dy = pos.y() + 0.5 - center[1];
            double dz = pos.z() + 0.5 - center[2];
            max = Math.max(max, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return max;
    }

    /**
     * Доля блоков в кадре с точки на луче вокруг их центра — для грубой разведки сцен
     * ({@link ScenePlanner}): тот же вопрос «влезает ли это в кадр целиком», которым потом
     * задаётся настоящий поиск, только без перебора.
     */
    public static double framedShare(Collection<Pos> blocks, double azimuth, double elevation,
                                     double distance, double fovDegrees, double aspect,
                                     double safeZone) {
        double[] center = centerOf(blocks);
        double radius = radiusOf(blocks, center);
        return framedFraction(pointsOf(sample(blocks)), center, radius * AIM_LIFT, distance,
                azimuth, elevation, fovDegrees, aspect, safeZone);
    }

    /**
     * Какая доля снимаемого попадает в безопасную зону кадра из этой точки.
     *
     * <p>Считается ровно той же проекцией, какой блок увидит зритель, — с соотношением сторон
     * итогового видео и с тем же подъёмом прицела по правилу третей, что и в {@link #place}.
     */
    private static double framedFraction(List<double[]> points, double[] center, double aimOffsetY,
                                         double distance, double azimuth, double elevation,
                                         double fovDegrees, double aspect, double safeZone) {
        if (points.isEmpty()) {
            return 1;
        }
        double[] camera = CameraFraming.positionAround(center, distance, azimuth, elevation);
        double[] lookAt = {center[0], center[1] + aimOffsetY, center[2]};
        int inside = 0;
        for (double[] point : points) {
            double[] screen = CameraFraming.project(camera, lookAt, point, fovDegrees, aspect);
            if (screen != null && Math.abs(screen[0]) <= safeZone && Math.abs(screen[1]) <= safeZone) {
                inside++;
            }
        }
        return (double) inside / points.size();
    }

    /** Центры блоков как точки — то, что проверяет посадку в кадр. */
    private static List<double[]> pointsOf(Collection<Pos> blocks) {
        List<double[]> points = new ArrayList<>(blocks.size());
        for (Pos pos : blocks) {
            points.add(new double[]{pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5});
        }
        return points;
    }

    private static List<Pos> sample(Collection<Pos> blocks) {
        if (blocks.size() <= MAX_SAMPLES) {
            return new ArrayList<>(blocks);
        }
        List<Pos> all = new ArrayList<>(blocks);
        List<Pos> result = new ArrayList<>(MAX_SAMPLES);
        int stride = all.size() / MAX_SAMPLES;
        for (int i = 0; i < all.size() && result.size() < MAX_SAMPLES; i += stride) {
            result.add(all.get(i));
        }
        return result;
    }
}

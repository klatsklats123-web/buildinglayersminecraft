package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    /** Видимость важнее композиции, поэтому её вес на порядок больше. */
    private static final double VISIBILITY_WEIGHT = 10.0;
    /** Ближе этого угла к предыдущему плану ставить нельзя — склейка читается рывком. */
    private static final double MIN_TURN = 30;
    /** Дальше этого — перескок через ось, движение в кадре перевернётся. */
    private static final double MAX_TURN = 150;

    /** На сколько градусов поднимаем камеру над плоским слоем. */
    private static final double FLAT_LIFT = 25;
    /** Выше этого не поднимаемся ни при какой форме: дальше начинается мёртвый вид сверху. */
    private static final double ELEVATION_CEILING = 62;

    private ShotPlanner() {
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
        double[] center = centerOf(targets);
        double radius = radiusOf(targets, center);
        double distance = CameraFraming.distanceFor(radius, fovDegrees, style.margin());
        List<Pos> samples = sample(targets);

        // Плоский слой — пол, фундамент, потолок — с малой высоты виден с торца, то есть
        // никак. Поднимаем камеру тем сильнее, чем слой площе; вертикальные стены
        // остаются на своей высоте.
        double lift = FLAT_LIFT * flatness(targets);
        double minElevation = Math.min(style.minElevation() + lift, ELEVATION_CEILING);
        double maxElevation = Math.min(style.maxElevation() + lift, ELEVATION_CEILING);

        double bestScore = Double.NEGATIVE_INFINITY;
        double bestAzimuth = 45;
        double bestElevation = (minElevation + maxElevation) / 2;

        for (int a = 0; a < AZIMUTH_STEPS; a++) {
            double azimuth = a * 360.0 / AZIMUTH_STEPS;
            for (int e = 0; e < ELEVATION_STEPS; e++) {
                double elevation = minElevation
                        + e * (maxElevation - minElevation) / (ELEVATION_STEPS - 1);

                double[] candidate = CameraFraming.positionAround(center, distance, azimuth, elevation);
                double visibility = Occlusion.visibleFraction(candidate, samples, occluders);
                double score = visibility * VISIBILITY_WEIGHT
                        + threeQuarterScore(azimuth)
                        + elevationScore(elevation, minElevation, maxElevation)
                        + turnScore(azimuth, previousAzimuth);

                if (score > bestScore) {
                    bestScore = score;
                    bestAzimuth = azimuth;
                    bestElevation = elevation;
                }
            }
        }
        return place(center, radius, distance, bestAzimuth, bestElevation, tick);
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
        List<CameraShot> shots = new ArrayList<>();
        if (front.isEmpty() || !style.follows()) {
            shots.add(start.shot());
            if (style.moving() && endTick > start.shot().tick()) {
                double[] center = centerOf(targets);
                shots.add(place(center, radiusOf(targets, center),
                        start.distance() * style.distanceRatio(),
                        start.azimuth() + style.arcDegrees(), start.elevation(), endTick).shot());
            }
            return shots;
        }

        double[] layerCenter = centerOf(targets);
        double layerRadius = radiusOf(targets, layerCenter);
        double blend = style.contextBlend();
        int total = front.size();

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

            double azimuth = start.azimuth() + style.arcDegrees() * progress;
            shots.add(place(aim, radius, distance, azimuth, start.elevation(), sample.tick()).shot());
        }
        return shots;
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
        // кадра, а не делила его пополам.
        double[] aim = {center[0], center[1] + radius * 0.18, center[2]};
        float[] angles = CameraFraming.lookAt(position, aim);
        return new Placement(
                new CameraShot(tick, position[0], position[1], position[2], angles[0], angles[1]),
                azimuth, elevation, distance);
    }

    /**
     * Насколько ракурс похож на три четверти: максимум на диагоналях, ноль — строго вдоль
     * оси, то есть в лоб стене.
     */
    static double threeQuarterScore(double azimuth) {
        double offset = Math.abs(((azimuth % 90) + 90) % 90 - 45);
        return 1.0 - offset / 45.0;
    }

    /** Середина диапазона высот лучше краёв: у самых границ планы однообразны. */
    private static double elevationScore(double elevation, double minElevation, double maxElevation) {
        double middle = (minElevation + maxElevation) / 2;
        double half = Math.max(1.0e-6, (maxElevation - minElevation) / 2);
        return 0.6 * (1.0 - Math.abs(elevation - middle) / half);
    }

    /**
     * Насколько слой плоский: 1 — горизонтальный лист вроде пола, 0 — стена или объёмный кусок.
     *
     * <p>Считается по отношению высоты к большей из горизонтальных сторон. Половина и выше
     * означает, что слой достаточно вертикален и поднимать камеру незачем.
     */
    static double flatness(Collection<Pos> blocks) {
        if (blocks.isEmpty()) {
            return 0;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos pos : blocks) {
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }
        double vertical = maxY - minY + 1;
        double horizontal = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        double ratio = vertical / Math.max(1.0, horizontal);
        return Math.max(0.0, Math.min(1.0, 1.0 - ratio * 2.0));
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
    static double shortestTurn(double degrees) {
        return ((degrees % 360) + 540) % 360 - 180;
    }

    static double[] centerOf(Collection<Pos> blocks) {
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

    static double radiusOf(Collection<Pos> blocks, double[] center) {
        double max = 1;
        for (Pos pos : blocks) {
            double dx = pos.x() + 0.5 - center[0];
            double dy = pos.y() + 0.5 - center[1];
            double dz = pos.z() + 0.5 - center[2];
            max = Math.max(max, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return max;
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

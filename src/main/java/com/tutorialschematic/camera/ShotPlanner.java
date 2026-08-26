package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Подбор ракурса на слой: перебираем точки вокруг и берём ту, откуда видно больше всего.
 *
 * <p>Перебор честный, а не эвристика «наверное, лучше спереди»: из каждой точки-кандидата
 * пускаются лучи в блоки слоя, и считается, сколько долетело мимо уже построенного.
 *
 * <p>Подъём камеры перебирается только внутри диапазона доктрины. Без этого ограничения
 * победителем всегда становится зенит — сверху заслонять нечем, — а снятое сверху выглядит
 * мёртво. Ограничение и делает выбор операторским, а не геометрическим.
 */
public final class ShotPlanner {

    /** Сколько направлений по кругу пробуем. 24 — это через каждые 15 градусов. */
    private static final int AZIMUTH_STEPS = 24;
    /** Сколько высот внутри диапазона доктрины. */
    private static final int ELEVATION_STEPS = 3;
    /** Больше стольки блоков в проверке видимости не участвует — считать дольше незачем. */
    private static final int MAX_SAMPLES = 160;

    private ShotPlanner() {
    }

    /**
     * Лучший ракурс на набор блоков.
     *
     * @param targets    что снимаем
     * @param occluders  что может заслонить — блоки, уже стоящие в мире
     * @param style      доктрина: насколько близко и с какой высоты
     * @param fovDegrees угол обзора камеры
     * @param tick       тик записи, на который встанет кадр
     */
    public static CameraShot plan(Collection<Pos> targets, Set<Pos> occluders,
                                  ShotStyle style, double fovDegrees, int tick) {
        double[] center = centerOf(targets);
        double radius = radiusOf(targets, center);
        double distance = CameraFraming.distanceFor(radius, fovDegrees, style.margin());
        List<Pos> samples = sample(targets);

        double bestScore = -1;
        double[] bestPosition = null;

        for (int a = 0; a < AZIMUTH_STEPS; a++) {
            double azimuth = a * 360.0 / AZIMUTH_STEPS;
            for (int e = 0; e < ELEVATION_STEPS; e++) {
                double elevation = ELEVATION_STEPS == 1
                        ? style.minElevation()
                        : style.minElevation() + e * (style.maxElevation() - style.minElevation()) / (ELEVATION_STEPS - 1);

                double[] candidate = CameraFraming.positionAround(center, distance, azimuth, elevation);
                double score = Occlusion.visibleFraction(candidate, samples, occluders);

                // При равной видимости берём ту, что ниже: приземлённый ракурс живее,
                // а перебор идёт от нижней границы диапазона вверх.
                if (score > bestScore + 1.0e-9) {
                    bestScore = score;
                    bestPosition = candidate;
                }
            }
        }

        if (bestPosition == null) {
            bestPosition = CameraFraming.positionAround(center, distance, 0, style.minElevation());
        }
        float[] angles = CameraFraming.lookAt(bestPosition, center);
        return new CameraShot(tick, bestPosition[0], bestPosition[1], bestPosition[2], angles[0], angles[1]);
    }

    /**
     * Пара кадров для пролёта: та же высота и расстояние, но камера едет по дуге вокруг
     * слоя. Дуга небольшая — сильный облёт за время одного слоя выглядит суетливо.
     */
    public static List<CameraShot> planFlight(Collection<Pos> targets, Set<Pos> occluders,
                                              ShotStyle style, double fovDegrees,
                                              int startTick, int endTick, double arcDegrees) {
        CameraShot anchor = plan(targets, occluders, style, fovDegrees, startTick);
        double[] center = centerOf(targets);

        double dx = anchor.x() - center[0];
        double dy = anchor.y() - center[1];
        double dz = anchor.z() - center[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double elevation = Math.toDegrees(Math.atan2(dy, horizontal));
        double azimuth = Math.toDegrees(Math.atan2(dx, dz));

        double[] from = CameraFraming.positionAround(center, distance, azimuth - arcDegrees / 2, elevation);
        double[] to = CameraFraming.positionAround(center, distance, azimuth + arcDegrees / 2, elevation);

        float[] fromAngles = CameraFraming.lookAt(from, center);
        float[] toAngles = CameraFraming.lookAt(to, center);

        List<CameraShot> shots = new ArrayList<>(2);
        shots.add(new CameraShot(startTick, from[0], from[1], from[2], fromAngles[0], fromAngles[1]));
        shots.add(new CameraShot(endTick, to[0], to[1], to[2], toAngles[0], toAngles[1]));
        return shots;
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

    /** Равномерная выборка по списку — считать видимость по всем блокам стены незачем. */
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

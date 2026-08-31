package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.Set;

/**
 * Безопасна ли траектория между двумя точками камеры, а не только сами точки.
 *
 * <p>Проверенные концы отрезка не значат безопасный проезд между ними: Flashback ведёт
 * плавные кадры кривой, а она умеет выгибаться за пределы прямой между двумя точками —
 * особенно на повороте вокруг угла постройки. Здесь — прямая проверка серии промежуточных
 * точек по прямой между началом и концом; грубее настоящей кривой Flashback, зато без знания
 * о соседних кадрах (кривая строится по четырём точкам подряд, а не по двум) и достаточно,
 * чтобы поймать «прямо посередине там стена».
 */
public final class SafetyValidator {

    /** Сколько промежуточных точек проверяем между двумя кадрами плавного перехода. */
    private static final int TRAJECTORY_SAMPLES = 6;

    private SafetyValidator() {
    }

    /**
     * @return {@code true}, если ни одна из промежуточных точек прямой между {@code from}
     * и {@code to} не попадает в {@code solid}
     */
    public static boolean isTrajectorySafe(double[] from, double[] to, Set<Pos> solid) {
        for (int i = 1; i < TRAJECTORY_SAMPLES; i++) {
            double t = (double) i / TRAJECTORY_SAMPLES;
            double x = lerp(from[0], to[0], t);
            double y = lerp(from[1], to[1], t);
            double z = lerp(from[2], to[2], t);
            Pos cell = new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (solid.contains(cell)) {
                return false;
            }
        }
        return true;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}

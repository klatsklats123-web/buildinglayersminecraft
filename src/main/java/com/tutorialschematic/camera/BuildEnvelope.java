package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.Collection;

/**
 * Огибающий габарит всей постройки — граница между «снаружи, как оператор» и «внутри дома».
 *
 * <p>До этого класса у системы не было самого понятия «снаружи»: единственным
 * пространственным ограничением было «камера не внутри твёрдого блока», а стоять посреди
 * комнаты этому правилу не противоречит. Поскольку камера всегда лежала на сфере вокруг
 * центроида снимаемого, а центроид кольца стен (и вообще всего, что огибает постройку)
 * физически внутри здания, от 18% до 39% кадров снималось изнутри дома — главная жалоба,
 * пережившая все итерации подбора весов.
 *
 * <p>Габарит считается по <b>всей</b> схеме один раз: оператор знает, где встанет дом, и
 * держится за линией застройки с самого первого слоя, а не переезжает, когда стены дорастут
 * до его точки.
 */
public final class BuildEnvelope {

    /**
     * Насколько граница отодвинута наружу от крайних блоков. Камера ровно на грани блока —
     * формально снаружи, а выглядит прижатой к стене вплотную; полтора блока — уже
     * положение человека, стоящего у стены, а не влипшего в неё.
     */
    private static final double MARGIN = 1.5;

    private final double minX, minY, minZ;
    private final double maxX, maxY, maxZ;

    private BuildEnvelope(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static BuildEnvelope around(Collection<Pos> blocks) {
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
        if (minX > maxX) {
            return new BuildEnvelope(0, 0, 0, 0, 0, 0);
        }
        // +1 — блок занимает клетку [x, x+1), габарит идёт по внешним граням крайних блоков
        return new BuildEnvelope(
                minX - MARGIN, minY - MARGIN, minZ - MARGIN,
                maxX + 1 + MARGIN, maxY + 1 + MARGIN, maxZ + 1 + MARGIN);
    }

    /** Внутри ли точка габарита (с учётом запаса {@link #MARGIN}). */
    public boolean contains(double[] point) {
        return point[0] >= minX && point[0] <= maxX
                && point[1] >= minY && point[1] <= maxY
                && point[2] >= minZ && point[2] <= maxZ;
    }

    /**
     * Дистанция вдоль луча камеры, начиная с которой она гарантированно снаружи габарита
     * и больше в него не вернётся.
     *
     * <p>Луч — тот же, по которому камеру ставит {@link CameraFraming#positionAround}:
     * из точки прицела в сторону азимута с подъёмом. Прицел может лежать где угодно, в том
     * числе глубоко внутри дома (центроид сцены-кольца) — тогда результат больше нуля, и
     * любая дистанция меньше него означает кадр изнутри. Если луч в габарит вообще не
     * попадает, вернётся ноль: снаружи можно стоять на любой дистанции.
     */
    public double exitDistance(double[] aim, double azimuthDegrees, double elevationDegrees) {
        double azimuth = Math.toRadians(azimuthDegrees);
        double elevation = Math.toRadians(elevationDegrees);
        double dx = Math.cos(elevation) * Math.sin(azimuth);
        double dy = Math.sin(elevation);
        double dz = Math.cos(elevation) * Math.cos(azimuth);

        // Классические «плиты»: по каждой оси интервал t, в котором луч внутри слоя бокса.
        // Пересечение интервалов пусто или целиком позади — луч снаружи с самого начала.
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;

        double[] origin = aim;
        double[] direction = {dx, dy, dz};
        double[] mins = {minX, minY, minZ};
        double[] maxs = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0e-12) {
                if (origin[axis] < mins[axis] || origin[axis] > maxs[axis]) {
                    return 0;
                }
                continue;
            }
            double t1 = (mins[axis] - origin[axis]) / direction[axis];
            double t2 = (maxs[axis] - origin[axis]) / direction[axis];
            enter = Math.max(enter, Math.min(t1, t2));
            exit = Math.min(exit, Math.max(t1, t2));
        }
        if (exit < 0 || enter > exit) {
            return 0;
        }
        // сантиметр наружу: точка ровно на границе после округлений может сыграть внутрь
        return exit + 0.01;
    }
}

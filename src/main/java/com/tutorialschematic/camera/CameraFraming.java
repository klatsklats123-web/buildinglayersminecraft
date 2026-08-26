package com.tutorialschematic.camera;

/**
 * Геометрия кадра: куда поставить камеру и куда её повернуть.
 *
 * <p>Ничего не знает ни о Minecraft, ни о Flashback — только числа, поэтому проверяется
 * обычными тестами. Углы считаются в той же системе, что и у камеры Flashback: она
 * восстанавливает направление взгляда как
 * {@code (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))}, отсюда и формулы ниже.
 */
public final class CameraFraming {

    /**
     * Насколько отодвинуться, чтобы шар радиуса {@code radius} влез в кадр целиком.
     *
     * <p>Половина угла обзора опирается на радиус: {@code sin(fov/2) = radius / distance}.
     * Запас нужен, чтобы постройка не упиралась в самые края кадра.
     *
     * @param radius     радиус того, что снимаем
     * @param fovDegrees вертикальный угол обзора камеры
     * @param margin     запас, 1.0 — впритык, 1.2 — комфортно
     */
    public static double distanceFor(double radius, double fovDegrees, double margin) {
        double half = Math.toRadians(Math.max(1.0, Math.min(179.0, fovDegrees)) / 2.0);
        return Math.max(1.0, radius * margin / Math.sin(half));
    }

    /**
     * Точка камеры: отходим от цели на {@code distance} в сторону, заданную азимутом и
     * подъёмом над горизонтом.
     *
     * @param azimuthDegrees   направление по кругу, 0 — со стороны +Z
     * @param elevationDegrees подъём над горизонтом; кино любит 10..40, зенит не любит
     */
    public static double[] positionAround(double[] target, double distance,
                                          double azimuthDegrees, double elevationDegrees) {
        double azimuth = Math.toRadians(azimuthDegrees);
        double elevation = Math.toRadians(elevationDegrees);
        double horizontal = distance * Math.cos(elevation);

        return new double[]{
                target[0] + horizontal * Math.sin(azimuth),
                target[1] + distance * Math.sin(elevation),
                target[2] + horizontal * Math.cos(azimuth)
        };
    }

    /**
     * Углы поворота камеры, стоящей в {@code from} и смотрящей в {@code to}.
     *
     * @return {@code [yaw, pitch]} в градусах
     */
    public static float[] lookAt(double[] from, double[] to) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length < 1.0e-9) {
            return new float[]{0f, 0f};
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.asin(dy / length));
        return new float[]{yaw, pitch};
    }

    /** Направление взгляда по углам — ровно так же, как его восстанавливает Flashback. */
    public static double[] direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new double[]{
                -Math.sin(yawRad) * cosPitch,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch
        };
    }

    private CameraFraming() {
    }
}

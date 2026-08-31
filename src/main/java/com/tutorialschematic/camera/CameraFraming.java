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
     * Экранные координаты точки: {@code [-1,1]} по обеим осям — это ровно края кадра.
     *
     * <p>Возвращает {@code null}, если точка позади камеры. Горизонталь делится ещё и на
     * соотношение сторон: вертикальный угол обзора у кадра один и тот же, а вот ширина у
     * шортса (9:16) почти вдвое меньше, чем у горизонтального видео — то, что спокойно
     * влезало в 16:9, в шортсе вылезает за край.
     *
     * @param aspect ширина, делённая на высоту: 16/9 для горизонтального, 9/16 для шортса
     */
    public static double[] project(double[] camera, double[] lookAt, double[] point,
                                   double fovDegrees, double aspect) {
        double[] forward = normalise(new double[]{
                lookAt[0] - camera[0], lookAt[1] - camera[1], lookAt[2] - camera[2]});
        if (forward == null) {
            return null;
        }
        double[] right = normalise(cross(forward, new double[]{0, 1, 0}));
        if (right == null) {
            return null;
        }
        double[] up = cross(right, forward);

        double[] offset = {point[0] - camera[0], point[1] - camera[1], point[2] - camera[2]};
        double depth = dot(offset, forward);
        if (depth <= 1.0e-6) {
            return null;
        }
        double half = Math.tan(Math.toRadians(Math.max(1.0, Math.min(179.0, fovDegrees)) / 2.0));
        return new double[]{
                dot(offset, right) / (depth * half * aspect),
                dot(offset, up) / (depth * half)
        };
    }

    /**
     * Насколько отодвинуться, чтобы <b>все</b> точки попали в безопасную зону кадра.
     *
     * <p>В отличие от {@link #distanceFor}, это не прикидка по ограничивающему шару, а прямая
     * проверка: точки проецируются на экран ровно так же, как их увидит камера, с учётом
     * соотношения сторон и настоящей формы снимаемого. Шар вокруг длинной низкой стены
     * огромен по сравнению с ней самой, и вписывание шара давало и слишком далёкий кадр там,
     * где не надо, и вылезающие за край блоки там, где надо было отойти.
     *
     * <p>Точное расстояние в лоб не решается (камера сама едет по лучу, и смещения точек в
     * кадре меняются вместе с ней), поэтому идём итерациями: во сколько раз худшая точка не
     * влезла — во столько же раз и отодвигаемся. Сходится за считанные шаги.
     *
     * @param safeZone доля кадра, дальше которой содержимое не пускаем: 1.0 — впритык к краям,
     *                 0.8 — с запасом, всё держится к центру
     * @param maxGrowth во сколько раз максимум разрешено отойти от начальной дистанции: у
     *                  сцены, которая окружает камеру со всех сторон (интерьер), «влезло всё»
     *                  недостижимо в принципе, и без потолка мы бы уехали в бесконечность
     */
    public static double distanceToFit(java.util.List<double[]> points, double[] center,
                                       double aimOffsetY, double azimuthDegrees, double elevationDegrees,
                                       double fovDegrees, double aspect, double safeZone,
                                       double startDistance, double maxGrowth) {
        if (points.isEmpty()) {
            return startDistance;
        }
        double[] lookAt = {center[0], center[1] + aimOffsetY, center[2]};
        double distance = startDistance;
        double limit = startDistance * maxGrowth;

        for (int attempt = 0; attempt < 8; attempt++) {
            double[] camera = positionAround(center, distance, azimuthDegrees, elevationDegrees);
            double worst = 1.0;
            for (double[] point : points) {
                double[] screen = project(camera, lookAt, point, fovDegrees, aspect);
                if (screen == null) {
                    // точка за спиной — отойти всё равно помогает, но осторожно
                    worst = Math.max(worst, 1.5);
                    continue;
                }
                worst = Math.max(worst, Math.abs(screen[0]) / safeZone);
                worst = Math.max(worst, Math.abs(screen[1]) / safeZone);
            }
            if (worst <= 1.001) {
                return distance;
            }
            distance = Math.min(limit, distance * Math.min(worst, 2.0));
            if (distance >= limit) {
                return limit;
            }
        }
        return distance;
    }

    private static double[] normalise(double[] vector) {
        double length = Math.sqrt(dot(vector, vector));
        if (length < 1.0e-9) {
            return null;
        }
        return new double[]{vector[0] / length, vector[1] / length, vector[2] / length};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
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

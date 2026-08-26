package com.tutorialschematic.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraFramingTest {

    /**
     * Главная проверка: посчитали углы на цель, восстановили по ним направление тем же
     * способом, что и Flashback, — и оно обязано указывать точно на цель.
     */
    private static void assertLooksAt(double[] from, double[] to) {
        float[] angles = CameraFraming.lookAt(from, to);
        double[] dir = CameraFraming.direction(angles[0], angles[1]);

        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        assertEquals(dx / length, dir[0], 1.0e-5, "по x");
        assertEquals(dy / length, dir[1], 1.0e-5, "по y");
        assertEquals(dz / length, dir[2], 1.0e-5, "по z");
    }

    @Test
    void взглядВосстанавливаетсяПоУглам() {
        assertLooksAt(new double[]{0, 0, 0}, new double[]{0, 0, 10});
        assertLooksAt(new double[]{0, 0, 0}, new double[]{10, 0, 0});
        assertLooksAt(new double[]{0, 0, 0}, new double[]{-7, 3, 4});
        assertLooksAt(new double[]{100, 64, -50}, new double[]{112, 70, -38});
        assertLooksAt(new double[]{5, 40, 5}, new double[]{5, 0, 5});
    }

    @Test
    void взглядСверхуВнизДаётПоложительныйНаклон() {
        // камера над целью — смотрит вниз, а в Minecraft это положительный pitch
        float[] angles = CameraFraming.lookAt(new double[]{0, 20, 0}, new double[]{0, 0, 0});
        assertEquals(90f, angles[1], 1.0e-3);
    }

    @Test
    void камераВстаётНаЗаданномПодъёмеИРасстоянии() {
        double[] target = {10, 64, -20};
        double distance = 30;
        double[] camera = CameraFraming.positionAround(target, distance, 45, 25);

        double dx = camera[0] - target[0], dy = camera[1] - target[1], dz = camera[2] - target[2];
        assertEquals(distance, Math.sqrt(dx * dx + dy * dy + dz * dz), 1.0e-6, "расстояние выдержано");

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        assertEquals(25.0, Math.toDegrees(Math.atan2(dy, horizontal)), 1.0e-6, "подъём выдержан");
    }

    @Test
    void камераВокругЦелиВсегдаНаНеёИСмотрит() {
        double[] target = {0, 70, 0};
        for (int azimuth = 0; azimuth < 360; azimuth += 30) {
            for (int elevation : new int[]{0, 15, 30, 45}) {
                double[] camera = CameraFraming.positionAround(target, 25, azimuth, elevation);
                assertLooksAt(camera, target);
            }
        }
    }

    @Test
    void чемШирожеУголОбзораТемБлижеМожноВстать() {
        double near = CameraFraming.distanceFor(10, 110, 1.0);
        double normal = CameraFraming.distanceFor(10, 70, 1.0);
        double narrow = CameraFraming.distanceFor(10, 30, 1.0);

        assertTrue(near < normal, "широкий угол — ближе");
        assertTrue(normal < narrow, "узкий угол — дальше");
    }

    @Test
    void радиусВлезаетВКадр() {
        // при расстоянии d и половине угла a должно выполняться sin(a) = r / d
        double radius = 12, fov = 70;
        double distance = CameraFraming.distanceFor(radius, fov, 1.0);
        double half = Math.toRadians(fov / 2);

        assertEquals(radius, distance * Math.sin(half), 1.0e-6);
    }

    @Test
    void запасОтодвигаетКамеру() {
        double tight = CameraFraming.distanceFor(10, 70, 1.0);
        double comfy = CameraFraming.distanceFor(10, 70, 1.2);
        assertEquals(tight * 1.2, comfy, 1.0e-6);
    }
}

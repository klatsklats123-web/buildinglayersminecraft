package com.tutorialschematic.lab;

import com.tutorialschematic.camera.CameraFraming;
import com.tutorialschematic.order.Pos;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Софтверная отрисовка блоков — ровно тем же взглядом, каким их посчитал алгоритм.
 *
 * <p>Принципиальный момент: проекция берётся из {@link CameraFraming#project} — той самой,
 * по которой {@link com.tutorialschematic.camera.ShotPlanner} решает, попал блок в кадр или
 * нет. Поэтому картинка в лаборатории не «похожа» на то, что видит алгоритм, а буквально
 * ему равна: если блок нарисовался за рамкой безопасной зоны, значит алгоритм тоже считает
 * его вылезшим, и наоборот. Свой отдельный рендер с собственной матрицей проекции такой
 * гарантии не давал бы.
 *
 * <p>Способ отрисовки самый простой из работающих: художник — блоки сортируются по
 * удалённости и рисуются от дальних к ближним, у каждого куба заполняются только те грани,
 * что смотрят на камеру. Z-буфера нет, теней нет; для задачи «видно ли блок и где он в
 * кадре» этого достаточно.
 */
public final class Scene3D {

    /** Блок к отрисовке: где он и каким цветом. */
    public record Item(Pos pos, Color colour) {
    }

    /**
     * Куда и чем смотрим. Пиксельный прямоугольник задаётся отдельно от панели, чтобы кадр
     * можно было letterbox-ить под соотношение сторон итогового видео.
     */
    public record Viewport(double[] camera, double[] lookAt, double fov, double aspect,
                           int x0, int y0, int width, int height) {

        /** Экранная точка мировой точки, либо {@code null}, если она позади камеры. */
        public double[] toScreen(double[] world) {
            double[] screen = CameraFraming.project(camera, lookAt, world, fov, aspect);
            if (screen == null) {
                return null;
            }
            return new double[]{
                    x0 + (screen[0] * 0.5 + 0.5) * width,
                    y0 + (0.5 - screen[1] * 0.5) * height
            };
        }

        /** Прямоугольник доли кадра: 1.0 — края кадра, 0.8 — безопасная зона. */
        public int[] boxOf(double fraction) {
            int w = (int) Math.round(width * fraction);
            int h = (int) Math.round(height * fraction);
            return new int[]{x0 + (width - w) / 2, y0 + (height - h) / 2, w, h};
        }
    }

    private static final double TOP_SHADE = 1.0;
    private static final double SIDE_X_SHADE = 0.78;
    private static final double SIDE_Z_SHADE = 0.6;

    private Scene3D() {
    }

    public static void paint(Graphics2D g, Viewport viewport, List<Item> items) {
        double[] camera = viewport.camera();
        List<Item> sorted = new ArrayList<>(items);
        // Художник: сначала дальние. Без этого ближние блоки затирались бы дальними.
        sorted.sort(Comparator.comparingDouble((Item item) -> -distanceSquared(camera, item.pos())));

        for (Item item : sorted) {
            paintCube(g, viewport, item);
        }
    }

    private static double distanceSquared(double[] camera, Pos pos) {
        double dx = pos.x() + 0.5 - camera[0];
        double dy = pos.y() + 0.5 - camera[1];
        double dz = pos.z() + 0.5 - camera[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static void paintCube(Graphics2D g, Viewport viewport, Item item) {
        Pos pos = item.pos();
        double[] camera = viewport.camera();
        double x = pos.x(), y = pos.y(), z = pos.z();

        // Рисуем только грани, обращённые к камере: остальные всё равно перекрыты своим же кубом.
        if (camera[1] > y + 1) {
            face(g, viewport, item.colour(), TOP_SHADE,
                    new double[][]{{x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}});
        } else if (camera[1] < y) {
            face(g, viewport, item.colour(), SIDE_Z_SHADE * 0.8,
                    new double[][]{{x, y, z}, {x + 1, y, z}, {x + 1, y, z + 1}, {x, y, z + 1}});
        }
        if (camera[0] > x + 1) {
            face(g, viewport, item.colour(), SIDE_X_SHADE,
                    new double[][]{{x + 1, y, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}});
        } else if (camera[0] < x) {
            face(g, viewport, item.colour(), SIDE_X_SHADE,
                    new double[][]{{x, y, z}, {x, y + 1, z}, {x, y + 1, z + 1}, {x, y, z + 1}});
        }
        if (camera[2] > z + 1) {
            face(g, viewport, item.colour(), SIDE_Z_SHADE,
                    new double[][]{{x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}});
        } else if (camera[2] < z) {
            face(g, viewport, item.colour(), SIDE_Z_SHADE,
                    new double[][]{{x, y, z}, {x + 1, y, z}, {x + 1, y + 1, z}, {x, y + 1, z}});
        }
    }

    private static void face(Graphics2D g, Viewport viewport, Color colour, double shade, double[][] corners) {
        Polygon polygon = new Polygon();
        for (double[] corner : corners) {
            double[] screen = viewport.toScreen(corner);
            if (screen == null) {
                // хоть один угол за спиной — куб рвётся по краю экрана, такую грань пропускаем
                return;
            }
            polygon.addPoint((int) Math.round(screen[0]), (int) Math.round(screen[1]));
        }
        g.setColor(shaded(colour, shade));
        g.fillPolygon(polygon);
    }

    private static Color shaded(Color colour, double shade) {
        return new Color(
                (int) Math.max(0, Math.min(255, colour.getRed() * shade)),
                (int) Math.max(0, Math.min(255, colour.getGreen() * shade)),
                (int) Math.max(0, Math.min(255, colour.getBlue() * shade)),
                colour.getAlpha());
    }
}

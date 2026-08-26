package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.Collection;
import java.util.Set;

/**
 * Проверка, сколько из снимаемого реально видно из точки камеры.
 *
 * <p>Ровно та задача, из-за которой всё затевалось: уже построенные слои заслоняют тот,
 * который сейчас строится. Мы знаем координаты и тех, и других, поэтому не гадаем —
 * пускаем лучи и считаем, сколько долетело.
 *
 * <p>Луч идёт мелкими шагами и на каждом смотрит, в какой клетке оказался. Способ грубый,
 * зато без краевых случаев вроде проскока сквозь угол между блоками, и его хватает:
 * ошибка в полклетки на выборе ракурса не сказывается.
 */
public final class Occlusion {

    /** Шаг луча в долях блока. Мельче — точнее и медленнее, крупнее — начинает пролетать стены. */
    private static final double STEP = 0.4;

    private Occlusion() {
    }

    /**
     * Доля целей, видимых из точки камеры.
     *
     * @param camera    откуда смотрим
     * @param targets   что хотим увидеть — блоки снимаемого слоя
     * @param occluders что может заслонить — блоки уже построенных слоёв
     * @return от 0 (не видно ничего) до 1 (видно всё)
     */
    public static double visibleFraction(double[] camera, Collection<Pos> targets, Set<Pos> occluders) {
        if (targets.isEmpty()) {
            return 0;
        }
        int visible = 0;
        for (Pos target : targets) {
            if (isVisible(camera, target, occluders)) {
                visible++;
            }
        }
        return (double) visible / targets.size();
    }

    /** Долетит ли луч от камеры до центра блока, не упёршись в чужой блок по дороге. */
    public static boolean isVisible(double[] camera, Pos target, Set<Pos> occluders) {
        double tx = target.x() + 0.5, ty = target.y() + 0.5, tz = target.z() + 0.5;
        double dx = tx - camera[0], dy = ty - camera[1], dz = tz - camera[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length < STEP) {
            return true;
        }
        int steps = (int) (length / STEP);
        double sx = dx / steps, sy = dy / steps, sz = dz / steps;

        double x = camera[0], y = camera[1], z = camera[2];
        // последний шаг пропускаем: там уже сама цель, и она заслоняет себя же
        for (int i = 0; i < steps - 1; i++) {
            x += sx;
            y += sy;
            z += sz;
            Pos cell = new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (cell.equals(target)) {
                continue;
            }
            if (occluders.contains(cell)) {
                return false;
            }
        }
        return true;
    }
}

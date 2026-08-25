package com.tutorialschematic.order;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Расстояние не по прямой, а по самой постройке — обходом в ширину от блоков-затравок.
 *
 * <p>Обычные формулы вычисляются по координатам, поэтому не знают о дырках: фронт волны
 * идёт сквозь пустоту так же, как через стену, и на обводке крыши перепрыгивает на другую
 * сторону дома. Здесь шаг возможен только на соседний блок <b>того же слоя</b>, поэтому
 * величина сама обтекает пустоту и идёт по конструкции.
 *
 * <p><b>Соседство берётся с диагоналями</b>, все 26 клеток вокруг. По граням считать
 * нельзя: у скатной крыши кромка идёт ступеньками, соседние блоки касаются только ребром,
 * и обход оборвался бы на первом же шаге.
 *
 * <p>До кусков, не связанных с затравкой, пути нет. Им ставится одно общее значение на
 * единицу больше самого дальнего достижимого — то есть они уходят в конец, а между собой
 * их разведёт следующий уровень сортировки.
 */
public final class StructureDistance {

    /** Расстояние для блоков, до которых от затравки не дойти. Проставляется по факту. */
    private StructureDistance() {
    }

    /**
     * Считает расстояние до каждого блока.
     *
     * @param blocks блоки слоя
     * @param seeds  точки старта; пустой список означает «начать с первого блока по
     *               устойчивому порядку», чтобы величина работала и без выбора вручную
     * @return массив расстояний в том же порядке, что и {@code blocks}
     */
    public static double[] compute(List<Pos> blocks, List<Pos> seeds) {
        double[] result = new double[blocks.size()];
        if (blocks.isEmpty()) {
            return result;
        }

        Map<Pos, Integer> indexOf = new HashMap<>(blocks.size() * 2);
        for (int i = 0; i < blocks.size(); i++) {
            indexOf.putIfAbsent(blocks.get(i), i);
        }

        int[] distance = new int[blocks.size()];
        java.util.Arrays.fill(distance, -1);

        Deque<Integer> queue = new ArrayDeque<>();
        for (Pos seed : effectiveSeeds(blocks, seeds, indexOf)) {
            Integer index = indexOf.get(seed);
            if (index != null && distance[index] < 0) {
                distance[index] = 0;
                queue.add(index);
            }
        }

        int maxDistance = 0;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            Pos pos = blocks.get(current);
            int next = distance[current] + 1;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Integer neighbour = indexOf.get(new Pos(pos.x() + dx, pos.y() + dy, pos.z() + dz));
                        if (neighbour != null && distance[neighbour] < 0) {
                            distance[neighbour] = next;
                            maxDistance = Math.max(maxDistance, next);
                            queue.add(neighbour);
                        }
                    }
                }
            }
        }

        double unreachable = maxDistance + 1.0;
        for (int i = 0; i < distance.length; i++) {
            result[i] = distance[i] < 0 ? unreachable : distance[i];
        }
        return result;
    }

    /**
     * Затравки, от которых пойдёт обход. Если игрок ничего не выбрал, берём самый нижний
     * блок — «снизу вверх» привычнее всего, и величина сразу осмысленна без настройки.
     */
    private static List<Pos> effectiveSeeds(List<Pos> blocks, List<Pos> seeds, Map<Pos, Integer> indexOf) {
        List<Pos> usable = new ArrayList<>();
        for (Pos seed : seeds) {
            if (indexOf.containsKey(seed)) {
                usable.add(seed);
            }
        }
        if (!usable.isEmpty()) {
            return usable;
        }
        Pos lowest = blocks.get(0);
        for (Pos pos : blocks) {
            if (pos.y() < lowest.y()
                    || (pos.y() == lowest.y() && pos.x() < lowest.x())
                    || (pos.y() == lowest.y() && pos.x() == lowest.x() && pos.z() < lowest.z())) {
                lowest = pos;
            }
        }
        return List.of(lowest);
    }
}

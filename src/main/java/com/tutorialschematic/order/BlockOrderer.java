package com.tutorialschematic.order;

import com.tutorialschematic.formula.EvalContext;
import com.tutorialschematic.formula.Formula;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Превращает набор блоков слоя в очередь постройки по правилам {@link OrderConfig}.
 *
 * <p>Один и тот же вызов всегда даёт один и тот же результат: исходный набор сначала
 * приводится к устойчивому порядку, а совпадения ключей разрешаются по координатам.
 * Без этого превью показывало бы одну анимацию, а постройка выдавала другую.
 */
public final class BlockOrderer {

    private BlockOrderer() {
    }

    /** Порядок по умолчанию, если формулы сломаны: снизу вверх, слева направо. */
    private static final Comparator<Pos> STABLE = Comparator
            .comparingInt(Pos::y)
            .thenComparingInt(Pos::x)
            .thenComparingInt(Pos::z);

    public static List<Pos> order(Collection<Pos> blocks, OrderConfig config) {
        return order(blocks, config, List.of());
    }

    /**
     * То же, но с точками старта для переменной {@code d} — расстояния по самой постройке.
     *
     * @param seeds блоки, от которых пойдёт обход; пустой список означает «снизу»
     */
    public static List<Pos> order(Collection<Pos> blocks, OrderConfig config, List<Pos> seeds) {
        List<Pos> list = new ArrayList<>(blocks);
        if (list.size() <= 1) {
            return list;
        }
        list.sort(STABLE);

        List<Formula> formulas = new ArrayList<>();
        List<Boolean> descending = new ArrayList<>();
        for (SortKey key : config.keys()) {
            if (key.isValid()) {
                formulas.add(key.compiled());
                descending.add(key.descending());
            }
        }
        if (formulas.isEmpty()) {
            // все формулы сломаны — отдаём хотя бы предсказуемый порядок
            return list;
        }

        int n = list.size();
        int depth = formulas.size();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos p : list) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }

        // расстояние по постройке считаем один раз на слой: обход в ширину по всем блокам
        double[] structureDistance = StructureDistance.compute(list, seeds);

        EvalContext ctx = new EvalContext();
        ctx.setSeed(config.seed());
        ctx.setBounds(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, n);

        double[] keys = new double[n * depth];
        for (int i = 0; i < n; i++) {
            Pos p = list.get(i);
            ctx.setStructureDistance(structureDistance[i]);
            ctx.setBlock(p.x() - minX, p.y() - minY, p.z() - minZ, i);
            for (int k = 0; k < depth; k++) {
                double value;
                try {
                    value = formulas.get(k).eval(ctx);
                } catch (RuntimeException e) {
                    value = 0;
                }
                // NaN сломал бы сортировку и порядок стал бы непредсказуемым
                if (Double.isNaN(value)) {
                    value = 0;
                }
                keys[i * depth + k] = descending.get(k) ? -value : value;
            }
        }

        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        java.util.Arrays.sort(indices, (ia, ib) -> {
            int baseA = ia * depth, baseB = ib * depth;
            for (int k = 0; k < depth; k++) {
                int cmp = Double.compare(keys[baseA + k], keys[baseB + k]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            // ключи совпали — держимся исходного устойчивого порядка
            return Integer.compare(ia, ib);
        });

        List<Pos> result = new ArrayList<>(n);
        for (int index : indices) {
            result.add(list.get(index));
        }
        return result;
    }

    /**
     * Режет очередь на шаги анимации по {@link OrderConfig#batchSize()} блоков.
     * Удобно и для превью, и для исполнителя постройки.
     */
    public static List<List<Pos>> steps(List<Pos> ordered, OrderConfig config) {
        int size = Math.max(1, config.batchSize());
        List<List<Pos>> steps = new ArrayList<>((ordered.size() + size - 1) / size);
        for (int i = 0; i < ordered.size(); i += size) {
            steps.add(new ArrayList<>(ordered.subList(i, Math.min(ordered.size(), i + size))));
        }
        return steps;
    }
}

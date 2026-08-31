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
        return computeOrder(blocks, config, seeds).positions();
    }

    /**
     * Отсортированные блоки вместе с их ключами. Ключи нужны раскадровке «по фронту»:
     * шаг там — это группа блоков с совпавшими ключами, а не фиксированное их число.
     *
     * @param keys ключ каждого блока по уровням сортировки, либо {@code null},
     *             если все формулы сломаны и порядок пришлось взять запасной
     */
    private record Ordered(List<Pos> positions, double[][] keys) {
    }

    private static Ordered computeOrder(Collection<Pos> blocks, OrderConfig config, List<Pos> seeds) {
        List<Pos> list = new ArrayList<>(blocks);
        if (list.size() <= 1) {
            return new Ordered(list, keysOf(list));
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
            return new Ordered(list, null);
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
        // Точка старта задаёт не только расстояние по постройке, но и начало отсчёта угла:
        // круговая анимация должна пойти оттуда, куда показал игрок.
        ctx.setAngleOrigin(angleOfSeed(seeds, minX, minZ,
                (maxX - minX + 1 - 1) / 2.0, (maxZ - minZ + 1 - 1) / 2.0));

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
        double[][] sortedKeys = new double[n][];
        for (int i = 0; i < n; i++) {
            int index = indices[i];
            result.add(list.get(index));
            sortedKeys[i] = java.util.Arrays.copyOfRange(keys, index * depth, index * depth + depth);
        }
        return new Ordered(result, sortedKeys);
    }

    /**
     * Угол первой точки старта относительно центра слоя, в градусах.
     *
     * <p>Считается в той же системе, что и {@code a} в {@link EvalContext}: координаты
     * относительно минимального угла слоя, центр — середина габарита. Без точек старта
     * возвращается ноль, и угол отсчитывается от оси +X, как раньше.
     */
    private static double angleOfSeed(List<Pos> seeds, int minX, int minZ, double cx, double cz) {
        if (seeds == null || seeds.isEmpty()) {
            return 0;
        }
        Pos seed = seeds.get(0);
        double dx = (seed.x() - minX) - cx;
        double dz = (seed.z() - minZ) - cz;
        if (Math.abs(dx) < 1.0e-9 && Math.abs(dz) < 1.0e-9) {
            // точка старта ровно в центре: направления у неё нет, оставляем прежний отсчёт
            return 0;
        }
        return Math.toDegrees(Math.atan2(dz, dx));
    }

    /** Ключи-заглушки для вырожденного случая: каждый блок сам себе фронт. */
    private static double[][] keysOf(List<Pos> list) {
        double[][] keys = new double[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            keys[i] = new double[]{i};
        }
        return keys;
    }

    /**
     * Готовая раскадровка слоя: список шагов, в каждом — блоки, встающие одновременно.
     *
     * <p>Два способа резать, и выбирает между ними {@link OrderConfig#frontStep()}:
     *
     * <ul>
     *   <li><b>по счёту</b> — ровно {@code batchSize} блоков за шаг. Темп постоянный,
     *       но фигура на него не влияет;</li>
     *   <li><b>по фронту</b> — за шаг встают все блоки с <b>одинаковым значением формулы</b>.
     *       Ширину шага задаёт сама постройка: пока фронт идёт одной ниткой, за шаг
     *       встаёт один блок; раздвоился — два; сошлись обратно — снова один. Именно это
     *       нужно, когда волна ветвится по конструкции.</li>
     * </ul>
     *
     * <p>Значения сравниваются точно, без допуска. Для {@code d}, {@code y}, {@code r}
     * это ровно то, что нужно — там равные значения и означают «один фронт». А у дробных
     * формул вроде {@code x + sin(z*40)*3} совпадений почти не бывает, и режим честно
     * вырождается в один блок за шаг, а не склеивает случайно близкое.
     */
    public static List<List<Pos>> orderIntoSteps(Collection<Pos> blocks, OrderConfig config, List<Pos> seeds) {
        Ordered ordered = computeOrder(blocks, config, seeds);
        if (!config.frontStep() || ordered.keys() == null) {
            return steps(ordered.positions(), config);
        }

        List<List<Pos>> result = new ArrayList<>();
        List<Pos> positions = ordered.positions();
        double[][] keys = ordered.keys();

        int start = 0;
        for (int i = 1; i <= positions.size(); i++) {
            if (i == positions.size() || !java.util.Arrays.equals(keys[i - 1], keys[i])) {
                result.add(new ArrayList<>(positions.subList(start, i)));
                start = i;
            }
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

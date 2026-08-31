package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Делит порядок появления блоков на пространственно-визуальные участки работы.
 *
 * <p>Раньше вопрос ставился как «как разбить время на куски» ({@link BuildTimeline#windows}),
 * а резать или нет решал сначала азимут от центра постройки, потом видимость, потом
 * плоскостность — три независимых косвенных признака поверх временной сетки. Отсюда и брались
 * странные ракурсы: система гадала, где человеку захотелось бы сменить кадр, вместо того чтобы
 * посмотреть, что физически происходит на площадке.
 *
 * <p>Здесь вопрос другой: {@link com.tutorialschematic.schematic.BuildLayer#steps()} — это
 * полный порядок появления блоков, самая точная информация, какая вообще есть о работе. Этот
 * класс ничего не знает ни про камеры, ни про стили съёмки — только геометрию: куда движется
 * центр очередного шага и какую форму приобретает накопленное. Раскадровку под конкретную
 * дорожку строит {@link ScenePlanner} уже поверх готовых участков.
 */
public final class BuildAnalyzer {

    /** Грубая форма участка — то же самое отличие пол/столб/стена, которое видно на глаз. */
    public enum Shape {
        /** Плоский лист: пол, фундамент, крыша малого уклона. */
        FLAT,
        /** Растёт вверх с почти неизменным пятном на плане: столб, свая, дымоход. */
        VERTICAL,
        /** Вытянуто в одну сторону — стена, ряд, дорожка. У такой формы есть направление фронта. */
        LINEAR,
        /** Ничего из перечисленного — произвольный объёмный кусок. */
        BLOB
    }

    /**
     * Один участок работы: сплошной кусок общего порядка постройки, который держится одной
     * формы и (если применимо) одного направления фронта.
     *
     * @param direction азимут движения фронта в градусах, та же система отсчёта, что и у
     *                  {@link CameraFraming} — либо {@code NaN}, если форма {@link Shape#FLAT}
     *                  или {@link Shape#VERTICAL} и направление тут ничего не значит (пол
     *                  кладётся рядами в любую сторону, столб никуда не едет). Для {@code LINEAR}
     *                  и {@code BLOB} — реальное направление смещения фронта
     * @param startStep индекс первого шага участка в исходном списке (включительно)
     * @param endStep   индекс шага сразу за последним (исключительно)
     */
    public record WorkSegment(List<Pos> blocks, List<List<Pos>> steps, double[] center, double radius,
                               double direction, Shape shape, int startStep, int endStep,
                               int startTick, int endTick) {
    }

    /**
     * Меньше этого смещения по горизонтали между центром накопленного и центром нового шага
     * считаем шумом: вертикальный рост столба или локальная перестановка внутри уже занятого
     * места не должны читаться как поворот фронта.
     */
    private static final double MIN_DISPLACEMENT = 1.5;

    /**
     * Больше этого угла между направлением участка и новым шагом — участок закрывается: фронт
     * реально повернул (дошёл до угла стены, перешёл на другую сторону).
     */
    private static final double TURN_ANGLE = 55.0;

    /** Сглаживание направления: не дёргается от каждого шага, но реагирует на устойчивый поворот. */
    private static final double DIRECTION_SMOOTHING = 0.5;

    /**
     * Меньше этого блоков в накопленном — форма и направление ещё не показательны, копим
     * дальше, что бы они ни говорили. Первые несколько блоков любого куска сами по себе
     * неотличимы (один блок на 3 в высоту — это ещё не видно, столб или начало стены), и
     * без этого запаса участок успевал бы один раз ошибочно классифицироваться, а потом
     * тут же «передумать» и зря разрезаться на стыке с самим собой.
     */
    private static final int MIN_SEGMENT_BLOCKS = 9;

    /**
     * Форма и направление участка судятся не по всей его истории с самого начала, а по
     * последним примерно стольким блокам. Без этого длинный участок (полдома подряд одной
     * фазой) стабилизировался бы в габарит размером со весь дом — а такой габарит почти
     * кубический ни по одному признаку, и один новый шаг его практически не меняет: участок
     * застревал бы в BLOB навсегда, потому что «в среднем с начала» действительно ни на что
     * не похоже, хотя прямо сейчас кладётся, например, ровная стена.
     */
    private static final int WINDOW_BLOCKS = 24;

    /**
     * Предохранитель: участок не растёт бесконечно, даже если форма и направление ни разу не
     * переменились — например, вся площадка засыпана без выраженного фронта. Не рабочий
     * триггер, а страховка от вырожденного случая.
     */
    private static final int MAX_SEGMENT_BLOCKS = 6000;

    private BuildAnalyzer() {
    }

    /**
     * @param steps     все шаги постройки подряд, в порядке появления; может быть склейкой
     *                  нескольких слоёв — участки не знают о границах слоёв и вправе пройти
     *                  сквозь них, если фронт там реально не меняется
     * @param stepTicks тик начала каждого шага плюс один дополнительный элемент на конец
     *                  последнего — длина {@code steps.size() + 1}
     */
    public static List<WorkSegment> analyze(List<List<Pos>> steps, int[] stepTicks) {
        List<WorkSegment> result = new ArrayList<>();
        Accumulator acc = new Accumulator();

        for (int i = 0; i < steps.size(); i++) {
            List<Pos> step = steps.get(i);
            if (step.isEmpty()) {
                continue;
            }

            if (acc.isEmpty()) {
                acc.start(step, i, stepTicks[i]);
                continue;
            }

            double[] stepCentre = centreXZ(step);
            double[] recentCentre = acc.recentCentreXZ();
            double dx = stepCentre[0] - recentCentre[0];
            double dz = stepCentre[1] - recentCentre[1];
            double displacement = Math.hypot(dx, dz);
            double rawDirection = displacement >= MIN_DISPLACEMENT ? azimuth(dx, dz) : Double.NaN;

            Shape currentShape = acc.shape();
            Shape combinedShape = acc.shapeIfAdded(step);

            // Направление значит что-то и для BLOB — участок, которому не хватило вытянутости
            // на строгий LINEAR, всё равно двигается по площадке в какую-то сторону, и это
            // реальный сигнал. Не считаем его только для FLAT (пол кладётся рядами, ряд может
            // идти в любую сторону, это не поворот фронта) и VERTICAL (столб никуда не едет).
            boolean directionMatters = currentShape != Shape.FLAT && currentShape != Shape.VERTICAL;
            boolean directionBreak = directionMatters
                    && !Double.isNaN(acc.direction) && !Double.isNaN(rawDirection)
                    && Math.abs(ShotPlanner.shortestTurn(rawDirection - acc.direction)) > TURN_ANGLE;
            boolean shapeBreak = combinedShape != currentShape;
            boolean sizeBreak = acc.blockCount() >= MAX_SEGMENT_BLOCKS;

            if (acc.blockCount() >= MIN_SEGMENT_BLOCKS && (directionBreak || shapeBreak || sizeBreak)) {
                result.add(acc.finish(stepTicks[i]));
                acc = new Accumulator();
                acc.start(step, i, stepTicks[i]);
            } else {
                acc.add(step, rawDirection);
            }
        }

        if (!acc.isEmpty()) {
            result.add(acc.finish(stepTicks[steps.size()]));
        }
        return result;
    }

    private static double[] centreXZ(List<Pos> blocks) {
        double x = 0, z = 0;
        for (Pos pos : blocks) {
            x += pos.x() + 0.5;
            z += pos.z() + 0.5;
        }
        return new double[]{x / blocks.size(), z / blocks.size()};
    }

    /** Азимут в той же системе отсчёта, что и {@link CameraFraming}: 0 — сторона +Z. */
    private static double azimuth(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, dz));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    /** Классифицирует форму окна последних блоков — обёртка над агрегатным {@link #classify}. */
    private static Shape classifyWindow(Collection<Pos> window) {
        if (window.isEmpty()) {
            return Shape.BLOB;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Long> footprint = new HashSet<>();
        for (Pos pos : window) {
            minX = Math.min(minX, pos.x());
            maxX = Math.max(maxX, pos.x());
            minY = Math.min(minY, pos.y());
            maxY = Math.max(maxY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxZ = Math.max(maxZ, pos.z());
            footprint.add(((long) pos.x() << 32) ^ (pos.z() & 0xFFFFFFFFL));
        }
        return classify(minX, maxX, minY, maxY, minZ, maxZ, footprint.size());
    }

    /**
     * Классифицирует форму по габариту и занятости пола в плане. Берёт только агрегаты
     * (габарит и число занятых клеток плана), а не сырые блоки.
     */
    private static Shape classify(int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                  int footprintCells) {
        double vertical = maxY - minY + 1;
        double spanX = maxX - minX + 1;
        double spanZ = maxZ - minZ + 1;
        double horizontal = Math.max(spanX, spanZ);

        // Пятно в плане совсем небольшое (одна-две клетки) и растёт вверх — столб, свая.
        // Порог узкий нарочно: чуть шире — и первые же блоки любой стены (пятно тоже
        // начинается с одной клетки) читались бы как столб, а потом «передумывали».
        boolean tinyFootprint = spanX <= 2 && spanZ <= 2;
        if (tinyFootprint && vertical >= 3 && vertical >= horizontal * 1.5) {
            return Shape.VERTICAL;
        }

        // Низкое и заполненное — пол, а не кольцо стен: то же отношение высоты к габариту,
        // что и у ShotPlanner.flatness, но без множителя на заполненность — тот факт, что
        // соседний ряд пола ещё не начат (пол кладётся рядами, не сразу весь), не должен
        // читаться как «на самом деле это не пол». Заполненность здесь — только чтобы
        // отсечь совсем разреженную россыпь, не более того.
        double lowness = clamp(1.0 - vertical / Math.max(1.0, horizontal) * 2.0);
        double footprintArea = spanX * spanZ;
        double fill = footprintArea <= 0 ? 0 : Math.min(1.0, footprintCells / footprintArea);
        if (lowness > 0.6 && fill > 0.15) {
            return Shape.FLAT;
        }

        double elongation = Math.max(spanX, spanZ) / Math.max(1.0, Math.min(spanX, spanZ));
        if (elongation >= 2.2) {
            return Shape.LINEAR;
        }
        return Shape.BLOB;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Копит весь участок целиком (для итогового результата) и отдельно — скользящее окно
     * последних {@link #WINDOW_BLOCKS} блоков (для решения формы и направления прямо сейчас).
     */
    private static final class Accumulator {
        final List<Pos> blocks = new ArrayList<>();
        final List<List<Pos>> steps = new ArrayList<>();
        final Deque<Pos> window = new ArrayDeque<>();
        int startStep;
        int startTick;
        double direction = Double.NaN;

        boolean isEmpty() {
            return blocks.isEmpty();
        }

        int blockCount() {
            return blocks.size();
        }

        void start(List<Pos> step, int stepIndex, int tick) {
            startStep = stepIndex;
            startTick = tick;
            add(step, Double.NaN);
        }

        void add(List<Pos> step, double rawDirection) {
            blocks.addAll(step);
            steps.add(step);
            for (Pos pos : step) {
                window.addLast(pos);
            }
            while (window.size() > WINDOW_BLOCKS) {
                window.removeFirst();
            }
            if (!Double.isNaN(rawDirection)) {
                direction = Double.isNaN(direction) ? rawDirection
                        : direction + ShotPlanner.shortestTurn(rawDirection - direction) * DIRECTION_SMOOTHING;
            }
        }

        /** Центр окна последних блоков — не всего участка, чтобы не разбавлять его историей. */
        double[] recentCentreXZ() {
            if (window.isEmpty()) {
                return new double[]{0, 0};
            }
            double x = 0, z = 0;
            for (Pos pos : window) {
                x += pos.x() + 0.5;
                z += pos.z() + 0.5;
            }
            return new double[]{x / window.size(), z / window.size()};
        }

        Shape shape() {
            return classifyWindow(window);
        }

        /** Форма окна, если бы шаг уже добавили — состояние при этом не меняется. */
        Shape shapeIfAdded(List<Pos> step) {
            List<Pos> trial = new ArrayList<>(window);
            trial.addAll(step);
            if (trial.size() > WINDOW_BLOCKS) {
                trial = trial.subList(trial.size() - WINDOW_BLOCKS, trial.size());
            }
            return classifyWindow(trial);
        }

        WorkSegment finish(int endTick) {
            double[] centre = ShotPlanner.centerOf(blocks);
            double radius = ShotPlanner.radiusOf(blocks, centre);
            Shape finalShape = shape();
            // Направление копилось на случай, если оно ещё понадобится, но у формы, для
            // которой оно не значит ничего (пол, столб), наружу его лучше не отдавать —
            // иначе кто-то по нему всё-таки сравнит два поворота стола там, где сравнивать нечего.
            double outDirection = finalShape == Shape.FLAT || finalShape == Shape.VERTICAL
                    ? Double.NaN : direction;
            return new WorkSegment(List.copyOf(blocks), List.copyOf(steps), centre, radius,
                    outDirection, finalShape, startStep, startStep + steps.size(), startTick, endTick);
        }
    }
}

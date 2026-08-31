package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Съёмка слоя: что это за фигура, из каких сторон она складывается и откуда её снимать.
 *
 * <p>Прежняя схема отталкивалась от центра масс снимаемого: посчитали середину блоков и
 * встали от неё на дистанции под каким-то углом. У кольца стен центр масс лежит внутри
 * здания, поэтому камера туда и заезжала — от 11 до 39 процентов кадров снимались изнутри
 * дома, и никакие штрафы этого не выправляли.
 *
 * <p>Здесь точка отсчёта другая — <b>сама постройка</b>. У слоя есть форма, у формы есть
 * стороны, и камера ставится снаружи напротив той стороны, которую сейчас кладут. Положение
 * задаётся геометрией дома, а не оптимизацией по сфере, поэтому попасть внутрь она не может
 * в принципе.
 */
public final class LayerShots {

    /** Что за фигура получается из слоя и как по ней расходится работа. */
    public enum Form {
        /** Горизонтальный лист: пол, потолок. Сторон нет, показывать надо саму плоскость. */
        PLANE,
        /** Кольцо, которое кладут стороной за стороной — каждая сторона снимается отдельно. */
        BY_SIDE,
        /** Всё растёт разом: кольцо по высоте, столбы в разных углах. Снимаем целиком. */
        WHOLE
    }

    /** Сторона постройки, к которой обращён кусок работы. */
    public enum Facing {
        SOUTH(0), EAST(90), NORTH(180), WEST(270), NONE(Double.NaN);

        private final double azimuth;

        Facing(double azimuth) {
            this.azimuth = azimuth;
        }

        /**
         * Куда отходить камере, чтобы встать снаружи напротив этой стороны.
         *
         * <p>Отсчёт тот же, что у {@link CameraFraming#positionAround}: ноль — сторона +Z.
         * Южная стена смотрит в +Z, значит и камера отходит в +Z.
         */
        public double azimuth() {
            return azimuth;
        }

        public String label() {
            return switch (this) {
                case NORTH -> "северная";
                case EAST -> "восточная";
                case SOUTH -> "южная";
                case WEST -> "западная";
                case NONE -> "целиком";
            };
        }
    }

    /** Кусок работы, который снимается одним ракурсом. */
    public record Unit(List<Pos> blocks, List<List<Pos>> steps, Form form, Facing facing,
                       int startTick, int endTick, int firstGlobalStep) {
    }

    /** Ниже этой высоты слой считается листом, а не объёмом. */
    private static final int PLANE_MAX_HEIGHT = 2;

    /**
     * Доля шагов, которым позволено задевать больше одной стороны, чтобы работа всё ещё
     * считалась идущей «стороной за стороной».
     *
     * <p>На реальной постройке слои с угловой формулой дают ровно ноль таких шагов, а слои,
     * которые кладут кольцами по высоте, — больше половины. Порог посередине надёжно
     * разделяет эти два случая.
     */
    private static final double MIXED_STEPS_LIMIT = 0.25;

    /**
     * Больше стольких участков одной стороны — работа мечется, сторону выделять нечего.
     *
     * <p>Обход обычно начинается с середины грани, поэтому у честного круга участков получается
     * пять: четыре стороны плюс возврат на первую в конце. Порог оставлен с запасом — постройка
     * может быть и не прямоугольной, — а слои, которые кладут кольцами по высоте, отсекает
     * не он, а доля смешанных шагов.
     */
    private static final int MAX_SIDE_RUNS = 12;

    /** Насколько поднимать камеру над листом: с малой высоты плоскость видна с торца. */
    private static final double PLANE_LIFT = 22;

    /** Углы, из которых выбирается ракурс, когда стороны нет: смотрим с угла, видно две грани. */
    private static final double[] CORNER_AZIMUTHS = {45, 135, 225, 315};

    /**
     * Короче этого кусок не снимается отдельным кадром, а прирастает к соседнему.
     *
     * <p>Резать строго по сторонам мало: обход по углу начинается с середины стены, поэтому
     * первая стена кладётся в два приёма — огрызок в начале и огрызок в конце. Камера честно
     * показывала первый огрызок пару секунд, уходила на соседнюю стену, а в финале
     * возвращалась ещё на пару секунд. Со стороны это выглядит как «ушла, не достроив».
     * Порог низкий нарочно: возвращаться в конце на первую грань — это нормально и нужно,
     * такой кадр обрезать нельзя. Склеиваются только настоящие огрызки, из которых кадра не
     * выйдет никак.
     */
    private static final int MIN_UNIT_TICKS = 25;

    /**
     * Но и не длиннее пятой части слоя: в коротком слое три секунды — это уже полноценный
     * кадр, а не огрызок, и склеивать там нечего.
     */
    private static final int SHORT_UNIT_SHARE = 5;

    private LayerShots() {
    }

    // ---- разбор слоя ----

    /**
     * Делит слой на куски, каждый из которых снимается одним ракурсом.
     *
     * @param firstGlobalStep номер первого шага слоя в общем порядке всей записи
     * @param built блоки уже построенных слоёв: по ним достраивается силуэт постройки
     */
    public static List<Unit> split(List<List<Pos>> steps, int startTick, int endTick,
                                   int firstGlobalStep, List<Pos> built) {
        List<Unit> units = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            return units;
        }
        List<Pos> all = flatten(steps);
        Silhouette silhouette = new Silhouette(all, built);
        Form form = formOf(all, steps, silhouette);

        if (form != Form.BY_SIDE) {
            units.add(new Unit(all, steps, form, Facing.NONE, startTick, endTick, firstGlobalStep));
            return units;
        }

        // Идём по шагам и режем там, где работа переходит на другую сторону. Границы — это
        // ровно те моменты, когда оператор и переставил бы камеру.
        int duration = Math.max(1, endTick - startTick);

        List<List<Pos>> runSteps = new ArrayList<>();
        Facing runFacing = null;
        int runFirstStep = 0;

        for (int i = 0; i < steps.size(); i++) {
            Facing facing = facingOfStep(steps.get(i), silhouette);
            if (runFacing == null) {
                runFacing = facing;
                runFirstStep = i;
            } else if (facing != runFacing) {
                units.add(runUnit(runSteps, runFacing, form, steps.size(), runFirstStep, i,
                        startTick, duration, firstGlobalStep));
                runSteps = new ArrayList<>();
                runFacing = facing;
                runFirstStep = i;
            }
            runSteps.add(steps.get(i));
        }
        if (!runSteps.isEmpty()) {
            units.add(runUnit(runSteps, runFacing, form, steps.size(), runFirstStep, steps.size(),
                    startTick, duration, firstGlobalStep));
        }
        return mergeShortUnits(units, duration);
    }

    /**
     * Приклеивает слишком короткие куски к соседям, пока все не станут смотрибельными.
     *
     * <p>Прирастает огрызок к тому соседу, где больше блоков: его сторона и остаётся главной
     * в кадре. Последний кусок никогда не остаётся один — если коротким оказался он, он уходит
     * в предыдущий.
     */
    private static List<Unit> mergeShortUnits(List<Unit> units, int layerDuration) {
        int threshold = Math.min(MIN_UNIT_TICKS, Math.max(1, layerDuration / SHORT_UNIT_SHARE));
        List<Unit> result = new ArrayList<>(units);
        while (result.size() > 1) {
            int shortest = -1;
            int shortestLength = Integer.MAX_VALUE;
            for (int i = 0; i < result.size(); i++) {
                int length = result.get(i).endTick() - result.get(i).startTick();
                // Последний кусок той же стороны, с которой начали, — это возврат на первую
                // грань, чтобы доложить оставшееся. Он короткий по своей природе и его нельзя
                // склеивать: без него зритель так и не увидит, чем стена закончилась. Но если
                // доложить осталось всего ничего, отдельный кадр из этого не выйдет — выйдет
                // вспышка, и лучше уж дать положить последний блок с соседней стороны.
                boolean returnToStart = i == result.size() - 1
                        && result.get(i).facing() == result.get(0).facing()
                        && length * 2 >= threshold;
                if (returnToStart) {
                    continue;
                }
                if (length < threshold && length < shortestLength) {
                    shortestLength = length;
                    shortest = i;
                }
            }
            if (shortest < 0) {
                break;
            }
            int into;
            if (shortest == 0) {
                into = 1;
            } else if (shortest == result.size() - 1) {
                into = shortest - 1;
            } else {
                into = result.get(shortest - 1).blocks().size() >= result.get(shortest + 1).blocks().size()
                        ? shortest - 1 : shortest + 1;
            }
            int first = Math.min(shortest, into);
            Unit merged = merge(result.get(first), result.get(first + 1));
            result.set(first, merged);
            result.remove(first + 1);
        }
        return joinSameSide(result);
    }

    /**
     * Склеивает соседей, оказавшихся на одной стороне.
     *
     * <p>Огрызок уходит к соседу и приносит с собой его сторону, поэтому после подрезки рядом
     * могут встать два куска с одной и той же стороной. Резать там нечего: камера стоит в том
     * же месте и смотрит туда же, а склейка посреди стены читается как лишняя смена ракурса.
     */
    private static List<Unit> joinSameSide(List<Unit> units) {
        List<Unit> result = new ArrayList<>(units);
        for (int i = result.size() - 1; i > 0; i--) {
            if (result.get(i - 1).facing() == result.get(i).facing()) {
                result.set(i - 1, merge(result.get(i - 1), result.get(i)));
                result.remove(i);
            }
        }
        return result;
    }

    private static Unit merge(Unit first, Unit second) {
        List<Pos> blocks = new ArrayList<>(first.blocks());
        blocks.addAll(second.blocks());
        List<List<Pos>> steps = new ArrayList<>(first.steps());
        steps.addAll(second.steps());
        // Главной остаётся сторона того куска, где блоков больше: она и должна читаться в кадре.
        Facing facing = first.blocks().size() >= second.blocks().size() ? first.facing() : second.facing();
        return new Unit(blocks, steps, first.form(), facing,
                first.startTick(), second.endTick(), first.firstGlobalStep());
    }

    private static Unit runUnit(List<List<Pos>> runSteps, Facing facing, Form form, int totalSteps,
                                int fromStep, int toStep, int layerStart, int duration, int firstGlobalStep) {
        int start = layerStart + (int) ((long) fromStep * duration / totalSteps);
        int end = layerStart + (int) ((long) toStep * duration / totalSteps);
        return new Unit(flatten(runSteps), List.copyOf(runSteps), form, facing,
                start, Math.max(start + 1, end), firstGlobalStep + fromStep);
    }

    /** Форма слоя и то, как по нему расходится работа. */
    public static Form formOf(List<Pos> all, List<List<Pos>> steps, List<Pos> built) {
        return formOf(all, steps, new Silhouette(all, built));
    }

    private static Form formOf(List<Pos> all, List<List<Pos>> steps, Silhouette silhouette) {
        int[] box = bounds(all);
        int spanY = box[4] - box[1] + 1;
        if (spanY <= PLANE_MAX_HEIGHT) {
            return Form.PLANE;
        }

        int mixed = 0;
        int runs = 0;
        Facing previous = null;
        for (List<Pos> step : steps) {
            if (sidesTouched(step, silhouette) > 1) {
                mixed++;
            }
            Facing facing = facingOfStep(step, silhouette);
            if (facing != previous) {
                runs++;
                previous = facing;
            }
        }
        boolean sideBySide = steps.size() > 0
                && (double) mixed / steps.size() < MIXED_STEPS_LIMIT
                && runs <= MAX_SIDE_RUNS;
        return sideBySide ? Form.BY_SIDE : Form.WHOLE;
    }

    /** К какой стороне отнести шаг — по большинству его блоков. */
    private static Facing facingOfStep(List<Pos> step, Silhouette silhouette) {
        Map<Facing, Integer> votes = new LinkedHashMap<>();
        for (Pos pos : step) {
            votes.merge(silhouette.facingOf(pos), 1, Integer::sum);
        }
        Facing best = Facing.NONE;
        int bestVotes = -1;
        for (Map.Entry<Facing, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > bestVotes) {
                bestVotes = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    private static int sidesTouched(List<Pos> step, Silhouette silhouette) {
        java.util.EnumSet<Facing> seen = java.util.EnumSet.noneOf(Facing.class);
        for (Pos pos : step) {
            seen.add(silhouette.facingOf(pos));
        }
        return seen.size();
    }

    /**
     * Силуэт постройки сверху: где проходит наружная кромка в каждом ряду и в каждом столбце.
     *
     * <p>Сторону блока раньше считали углом от центра слоя, и на настоящем доме это врало.
     * Дом четырёхугольный, но два его торца сужены на блок, поэтому восточная стена — это три
     * отрезка: длинный по внешней кромке и два коротких, утопленных внутрь у самых углов.
     * Утопленные отрезки лежат далеко по z, их угол от центра диагональный, и по углу они
     * отходили северу и югу — камера уезжала на соседнюю стену, не достроив восточную.
     *
     * <p>Кромка от такой формы не страдает: утопленный отрезок всё равно остаётся самым
     * восточным блоком в своём ряду, а значит его и видно с востока. Строится кромка по
     * постройке целиком, вместе с уже готовыми слоями: без столбов силуэт неоднозначен —
     * у стены без столба под ней угловой блок оказывается крайним сразу в двух направлениях.
     */
    private static final class Silhouette {
        private final Map<Integer, int[]> rows = new HashMap<>();
        private final Map<Integer, int[]> columns = new HashMap<>();
        private final double centreX;
        private final double centreZ;
        private final double halfX;
        private final double halfZ;

        Silhouette(List<Pos> layer, List<Pos> built) {
            for (Pos pos : layer) {
                add(pos);
            }
            if (built != null) {
                for (Pos pos : built) {
                    add(pos);
                }
            }
            double[] centre = footprintCentre(layer);
            int[] box = bounds(layer);
            this.centreX = centre[0];
            this.centreZ = centre[1];
            this.halfX = Math.max(0.5, (box[3] - box[0] + 1) / 2.0);
            this.halfZ = Math.max(0.5, (box[5] - box[2] + 1) / 2.0);
        }

        private void add(Pos pos) {
            rows.compute(pos.z(), (z, span) -> span == null
                    ? new int[]{pos.x(), pos.x()}
                    : new int[]{Math.min(span[0], pos.x()), Math.max(span[1], pos.x())});
            columns.compute(pos.x(), (x, span) -> span == null
                    ? new int[]{pos.z(), pos.z()}
                    : new int[]{Math.min(span[0], pos.z()), Math.max(span[1], pos.z())});
        }

        /**
         * Сторона, с которой блок виден: та кромка силуэта, на которой он лежит.
         *
         * <p>Угловой блок лежит сразу на двух кромках. Достаётся он той стороне, от которой
         * дальше отошёл в долях полуразмера постройки: у вытянутого дома угол торца — это
         * прежде всего торец. Блок, не попавший ни на одну кромку, спрятан за соседями, и
         * сторона у него та же, в какую он больше смещён от центра.
         */
        Facing facingOf(Pos pos) {
            double dx = pos.x() + 0.5 - centreX;
            double dz = pos.z() + 0.5 - centreZ;
            if (Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0) {
                return Facing.NONE;
            }
            double alongX = Math.abs(dx) / halfX;
            double alongZ = Math.abs(dz) / halfZ;

            int[] row = rows.get(pos.z());
            int[] column = columns.get(pos.x());
            Facing best = Facing.NONE;
            double bestReach = -1;
            if (row != null && dx > 0 && pos.x() == row[1] && alongX > bestReach) {
                best = Facing.EAST;
                bestReach = alongX;
            }
            if (row != null && dx < 0 && pos.x() == row[0] && alongX > bestReach) {
                best = Facing.WEST;
                bestReach = alongX;
            }
            if (column != null && dz > 0 && pos.z() == column[1] && alongZ > bestReach) {
                best = Facing.SOUTH;
                bestReach = alongZ;
            }
            if (column != null && dz < 0 && pos.z() == column[0] && alongZ > bestReach) {
                best = Facing.NORTH;
            }
            if (best != Facing.NONE) {
                return best;
            }
            if (alongX >= alongZ) {
                return dx > 0 ? Facing.EAST : Facing.WEST;
            }
            return dz > 0 ? Facing.SOUTH : Facing.NORTH;
        }
    }

    // ---- постановка камеры ----

    /**
     * Ставит камеру на кусок работы.
     *
     * <p>У стороны направление задано жёстко — камера идёт наружу по нормали к ней, и никакой
     * поиск по кругу тут не нужен: именно поиск и уводил её внутрь дома. Свободы остаются
     * только там, где стороны нет: у листа и у «растёт всё сразу» выбирается лучший из
     * четырёх угловых ракурсов, откуда видно сразу две грани.
     *
     * <p>Дистанция подбирается по силуэту: все блоки куска проецируются на экран ровно так,
     * как их увидит зритель, и камера отходит, пока силуэт целиком не сядет в безопасную зону.
     *
     * @param standing что уже стоит из схемы — заслоны для проверки видимости
     * @param solid    то же плюс посторонние блоки мира: в них нельзя оказаться самой камере
     * @param envelope граница застройки; камера обязана быть снаружи неё
     */
    public static CameraShot place(Unit unit, ShotStyle style, double fov, double aspect,
                                   Set<Pos> standing, Set<Pos> solid, BuildEnvelope envelope, int tick) {
        List<Pos> targets = unit.blocks();
        if (targets.isEmpty()) {
            return null;
        }
        double[] aim = ShotPlanner.centerOf(targets);
        double radius = ShotPlanner.radiusOf(targets, aim);
        double aimLift = radius * 0.18;
        double safeZone = style.safeZone();
        double elevation = elevationFor(unit, style);
        double base = CameraFraming.distanceFor(radius, fov, 1.0);
        List<double[]> silhouette = pointsOf(targets);

        double bestScore = Double.NEGATIVE_INFINITY;
        double bestAzimuth = 45;
        double bestDistance = base;

        for (double azimuth : azimuthsFor(unit, style)) {
            double distance = CameraFraming.distanceToFit(silhouette, aim, aimLift, azimuth, elevation,
                    fov, aspect, safeZone, base, 3.0);
            distance = outsideEnvelope(envelope, aim, azimuth, elevation, distance);
            distance = clearOfBlocks(aim, azimuth, elevation, distance, solid);

            double[] camera = CameraFraming.positionAround(aim, distance, azimuth, elevation);
            double visibility = Occlusion.visibleFraction(camera, targets, standing);
            // Видимость решает, но при равной видимости побеждает первый по списку — а список
            // у стороны состоит из одного направления, у остальных форм начинается с угла,
            // ближайшего к прошлому кадру.
            double score = visibility;
            if (score > bestScore) {
                bestScore = score;
                bestAzimuth = azimuth;
                bestDistance = distance;
            }
        }

        double[] position = CameraFraming.positionAround(aim, bestDistance, bestAzimuth, elevation);
        double[] lookAt = {aim[0], aim[1] + aimLift, aim[2]};
        float[] angles = CameraFraming.lookAt(position, lookAt);
        return new CameraShot(tick, position[0], position[1], position[2], angles[0], angles[1], true);
    }

    /** Направления, из которых выбираем: у стороны одно, у остальных форм — четыре угловых. */
    private static List<Double> azimuthsFor(Unit unit, ShotStyle style) {
        List<Double> result = new ArrayList<>();
        double offset = sideOffset(style);
        if (unit.facing() != Facing.NONE && !Double.isNaN(unit.facing().azimuth())) {
            result.add(norm(unit.facing().azimuth() + offset));
            return result;
        }
        for (double corner : CORNER_AZIMUTHS) {
            result.add(norm(corner + offset));
        }
        return result;
    }

    /**
     * На сколько градусов дорожка отходит от строгого перпендикуляра к стороне.
     *
     * <p>Строго в лоб стена выглядит плоско, поэтому часть дорожек смотрит чуть наискось —
     * так видно и саму стену, и уходящий вбок угол. Разные смещения ещё и разводят дорожки
     * между собой: иначе они отличались бы только дальностью.
     */
    private static double sideOffset(ShotStyle style) {
        return switch (style) {
            case NEAR_HOLD, NEAR_FOLLOW, NEAR_SIDE_HOLD -> -14;
            case FAR_HOLD, FAR_FOLLOW, FAR_SIDE_HOLD -> 22;
            case HIGH_HOLD, HIGH_FOLLOW -> 34;
            default -> 0;
        };
    }

    /** Высота камеры: середина диапазона доктрины, а над листом заметно выше. */
    private static double elevationFor(Unit unit, ShotStyle style) {
        double middle = (style.minElevation() + style.maxElevation()) / 2;
        return unit.form() == Form.PLANE ? Math.min(62, middle + PLANE_LIFT) : middle;
    }

    /** Отодвигает камеру до выхода за линию застройки. */
    private static double outsideEnvelope(BuildEnvelope envelope, double[] aim,
                                          double azimuth, double elevation, double distance) {
        if (envelope == null) {
            return distance;
        }
        return Math.max(distance, envelope.exitDistance(aim, azimuth, elevation));
    }

    /** Отодвигает камеру назад, пока её точка не выйдет из твёрдых блоков. */
    private static double clearOfBlocks(double[] aim, double azimuth, double elevation,
                                        double distance, Set<Pos> solid) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double[] position = CameraFraming.positionAround(aim, distance, azimuth, elevation);
            Pos cell = new Pos((int) Math.floor(position[0]), (int) Math.floor(position[1]),
                    (int) Math.floor(position[2]));
            if (!solid.contains(cell)) {
                return distance;
            }
            distance *= 1.15;
        }
        return distance;
    }

    // ---- мелочи ----

    private static List<double[]> pointsOf(List<Pos> blocks) {
        List<double[]> points = new ArrayList<>(blocks.size());
        for (Pos pos : blocks) {
            points.add(new double[]{pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5});
        }
        return points;
    }

    private static List<Pos> flatten(List<List<Pos>> steps) {
        List<Pos> all = new ArrayList<>();
        for (List<Pos> step : steps) {
            all.addAll(step);
        }
        return all;
    }

    /** Середина габарита слоя в плане: {@code [x, z]}. */
    private static double[] footprintCentre(List<Pos> blocks) {
        int[] box = bounds(blocks);
        return new double[]{(box[0] + box[3] + 1) / 2.0, (box[2] + box[5] + 1) / 2.0};
    }

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
    private static int[] bounds(List<Pos> blocks) {
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
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static double norm(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }
}

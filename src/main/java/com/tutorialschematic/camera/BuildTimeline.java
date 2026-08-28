package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Где идёт работа в каждый момент слоя.
 *
 * <p>Камера, нацеленная в геометрический центр слоя, показывает работу в центре кадра
 * только в середине слоя: фронт постройки едет, и к концу оказывается у края. Чтобы
 * держать его в центре, нужно знать, <b>где он в какой момент</b>.
 *
 * <p>Момент считается не по настройкам темпа, а по фактическим границам слоя в записи:
 * начало и конец берутся из меток, а внутри распределяются равномерно по шагам. Так
 * расчёт не зависит ни от множителя скорости, ни от пауз, ни от того, совпал ли темп
 * записи с задуманным.
 */
public final class BuildTimeline {

    /** Один замер: куда смотреть, насколько крупно и на каком тике. */
    public record FrontSample(int tick, double[] center, double radius) {
    }

    /**
     * Одно окно постройки слоя: тик начала и конца, и шаги, которые в нём встают —
     * ровно тот кусок раскадровки слоя, что нужен {@link #sample} для ведения камеры
     * внутри этого окна.
     */
    public record Window(int tick, int endTick, List<List<Pos>> steps) {
        /** Блоки окна одним списком — то, что снимается. */
        public List<Pos> blocks() {
            List<Pos> result = new ArrayList<>();
            for (List<Pos> step : steps) {
                result.addAll(step);
            }
            return result;
        }
    }

    /** Примерно столько тиков между замерами — около трёх секунд. */
    private static final int TICKS_PER_SAMPLE = 60;
    private static final int MIN_SAMPLES = 2;
    private static final int MAX_SAMPLES = 10;

    private BuildTimeline() {
    }

    /**
     * Разбивает слой на замеры движения фронта.
     *
     * @param steps     раскадровка слоя: в каждом шаге блоки, встающие одновременно
     * @param startTick тик записи, на котором слой начался
     * @param endTick   тик, на котором он закончился
     */
    public static List<FrontSample> sample(List<List<Pos>> steps, int startTick, int endTick) {
        List<FrontSample> result = new ArrayList<>();
        if (steps == null || steps.isEmpty() || endTick <= startTick) {
            return result;
        }

        int duration = endTick - startTick;
        int count = windowCount(duration, steps.size());

        for (int i = 0; i < count; i++) {
            int from = (int) ((long) i * steps.size() / count);
            int to = (int) ((long) (i + 1) * steps.size() / count);

            List<Pos> window = new ArrayList<>();
            for (int s = from; s < to; s++) {
                window.addAll(steps.get(s));
            }
            if (window.isEmpty()) {
                continue;
            }

            // Целимся в середину окна по времени: так камера идёт вместе с работой,
            // а не догоняет её и не забегает вперёд.
            int tick = startTick + (int) ((i + 0.5) / count * duration);
            double[] center = centerOf(window);
            result.add(new FrontSample(tick, center, radiusOf(window, center)));
        }
        return result;
    }

    /**
     * То же дробление, что и {@link #sample}, но окнами целиком, а не отдельными замерами —
     * единая нарезка слоя на фазы постройки, общая для всех дорожек.
     *
     * <p>Один ракурс на весь слой годится только для выпуклой формы. Слой-кольцо (стены,
     * экстерьер, крыша) огибает постройку со всех сторон, и ни один ракурс снаружи не
     * покажет больше двух его сторон разом — ни статичный, ни тем более ведущий: тот раньше
     * держал направление на весь слой и на кольце в какой-то момент упирался взглядом в уже
     * стоящее. Дробление на фазы решает оба случая одинаково: внутри фазы дорожка ведёт себя
     * как обычно (держит кадр или едет за фронтом), а между фазами ракурс выбирается заново
     * и склейка режется насухо, а не сплайном — см. {@link CameraShot#cut()}.
     */
    public static List<Window> windows(List<List<Pos>> steps, int startTick, int endTick) {
        List<Window> result = new ArrayList<>();
        if (steps == null || steps.isEmpty() || endTick <= startTick) {
            return result;
        }

        int duration = endTick - startTick;
        int count = windowCount(duration, steps.size());

        for (int i = 0; i < count; i++) {
            int from = (int) ((long) i * steps.size() / count);
            int to = (int) ((long) (i + 1) * steps.size() / count);

            List<List<Pos>> windowSteps = new ArrayList<>(steps.subList(from, to));
            if (windowSteps.stream().allMatch(List::isEmpty)) {
                continue;
            }

            int tick = startTick + (int) ((long) i * duration / count);
            int nextTick = i + 1 < count ? startTick + (int) ((long) (i + 1) * duration / count) : endTick;
            result.add(new Window(tick, nextTick, windowSteps));
        }
        return result;
    }

    /** Сколько окон на слой: примерно по одному на {@link #TICKS_PER_SAMPLE}, но не мельче шагов. */
    private static int windowCount(int duration, int stepsSize) {
        int count = Math.max(MIN_SAMPLES, Math.min(MAX_SAMPLES, duration / TICKS_PER_SAMPLE));
        return Math.min(count, stepsSize);
    }

    static double[] centerOf(List<Pos> blocks) {
        double x = 0, y = 0, z = 0;
        for (Pos pos : blocks) {
            x += pos.x() + 0.5;
            y += pos.y() + 0.5;
            z += pos.z() + 0.5;
        }
        return new double[]{x / blocks.size(), y / blocks.size(), z / blocks.size()};
    }

    static double radiusOf(List<Pos> blocks, double[] center) {
        double max = 1;
        for (Pos pos : blocks) {
            double dx = pos.x() + 0.5 - center[0];
            double dy = pos.y() + 0.5 - center[1];
            double dz = pos.z() + 0.5 - center[2];
            max = Math.max(max, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return max;
    }
}

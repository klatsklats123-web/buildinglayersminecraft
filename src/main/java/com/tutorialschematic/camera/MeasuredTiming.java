package com.tutorialschematic.camera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Замеренное время постройки: на каком тике записи встал каждый шаг каждого слоя.
 *
 * <p>Раньше время ракурса не замеряли, а <i>вычисляли</i>: брали промежуток между двумя
 * метками, делили пропорционально числу шагов и считали, что кладка шла ровно. Пока она
 * действительно идёт ровно, это работает. Но стоит игре на секунду задуматься — подгрузить
 * чанки, пережить рывок сервера — и расчёт разъезжается с тем, что было на самом деле:
 * камера уходит на следующую стену, пока предыдущую ещё кладут.
 *
 * <p>Здесь лежит только счёт. Кто снимает тики и куда их сохраняет — забота вызывающего.
 */
public final class MeasuredTiming {

    /**
     * Насколько замеренное начало слоя может расходиться с его меткой.
     *
     * <p>Метку ставит клиентский поток, а шаги считает серверный, и между ними всегда есть
     * зазор в тик. Расхождение больше этого означает, что журнал от другой записи.
     */
    public static final int MARKER_TOLERANCE = 3;

    /** Замеры одного слоя. */
    public static final class LayerLog {
        private int startTick = -1;
        private final List<Integer> stepTicks = new ArrayList<>();

        public int startTick() {
            return startTick;
        }

        public List<Integer> stepTicks() {
            return stepTicks;
        }
    }

    private final Map<Integer, LayerLog> layers = new LinkedHashMap<>();
    private int endTick = -1;

    public void reset() {
        layers.clear();
        endTick = -1;
    }

    public boolean isEmpty() {
        return layers.isEmpty();
    }

    public Map<Integer, LayerLog> layers() {
        return layers;
    }

    public int endTick() {
        return endTick;
    }

    public void setEndTick(int tick) {
        this.endTick = tick;
    }

    /** Слой начался: заодно сбрасываем его шаги, если слой переснимают. */
    public void layerStarted(int layerIndex, int tick) {
        LayerLog log = layers.computeIfAbsent(layerIndex, i -> new LayerLog());
        log.startTick = tick;
        log.stepTicks.clear();
    }

    /** Шаг слоя встал на этом тике. Шаги приходят по порядку, номер не нужен. */
    public void stepPlaced(int layerIndex, int tick) {
        layers.computeIfAbsent(layerIndex, i -> new LayerLog()).stepTicks.add(tick);
    }

    /** Кладёт готовый журнал слоя — для чтения с диска. */
    public void putLayer(int layerIndex, int startTick, List<Integer> stepTicks) {
        LayerLog log = new LayerLog();
        log.startTick = startTick;
        log.stepTicks.addAll(stepTicks);
        layers.put(layerIndex, log);
    }

    /**
     * Почему замеры к этому слою не подходят, либо {@code null}, если подходят.
     *
     * <p>Отдельным методом, чтобы причину можно было написать в журнал: молча вернуться к
     * расчёту по меткам хуже, чем сказать, из-за чего.
     */
    public String mismatch(int layerIndex, int totalSteps, int markerTick) {
        LayerLog log = layers.get(layerIndex);
        if (log == null) {
            return "замеров нет";
        }
        if (log.stepTicks.size() != totalSteps) {
            // анимацию правили после записи — номера шагов больше ни на что не ложатся
            return "записано шагов " + log.stepTicks.size() + ", сейчас " + totalSteps;
        }
        if (log.startTick >= 0 && Math.abs(log.startTick - markerTick) > MARKER_TOLERANCE) {
            return "замер начала " + log.startTick + " против метки " + markerTick;
        }
        return null;
    }

    /**
     * Переставляет куски слоя на замеренное время.
     *
     * <p>Возвращает исходный список, если замеры к слою не подходят — тогда время остаётся
     * вычисленным, как раньше.
     *
     * @param firstGlobalStep номер первого шага слоя в общем порядке всей записи
     * @param markerTick      тик метки слоя из реплея, по нему и сверяемся
     * @param layerEnd        конец слоя, он же начало задержки после него
     */
    public List<LayerShots.Unit> retime(int layerIndex, List<LayerShots.Unit> units,
                                        int firstGlobalStep, int totalSteps,
                                        int markerTick, int layerEnd) {
        if (units.isEmpty() || mismatch(layerIndex, totalSteps, markerTick) != null) {
            return units;
        }
        List<Integer> ticks = layers.get(layerIndex).stepTicks;

        List<LayerShots.Unit> result = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            LayerShots.Unit unit = units.get(i);
            int firstStep = unit.firstGlobalStep() - firstGlobalStep;
            if (firstStep < 0 || firstStep >= ticks.size()) {
                return units;
            }
            int start = ticks.get(firstStep);
            // Кусок кончается там, где начинается следующий: пустого времени между ними нет.
            int end = layerEnd;
            if (i + 1 < units.size()) {
                int nextStep = units.get(i + 1).firstGlobalStep() - firstGlobalStep;
                if (nextStep < 0 || nextStep >= ticks.size()) {
                    return units;
                }
                end = ticks.get(nextStep);
            }
            result.add(new LayerShots.Unit(unit.blocks(), unit.steps(), unit.form(), unit.facing(),
                    start, Math.max(start + 1, end), unit.firstGlobalStep()));
        }
        return result;
    }
}

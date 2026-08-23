package com.tutorialschematic.order;

/**
 * Отсчёт шагов постройки по тикам.
 *
 * <p>Шаг делается раз в {@code ticksPerStep} тиков, а ускорение увеличивает долю шага,
 * приходящуюся на один тик. Остаток копится дробью и переносится дальше, поэтому темп
 * меняется плавно: при округлении до целых тиков скорости x2 и x4 склеивались бы в одну.
 *
 * <p>Вынесено из исполнителя постройки отдельно и покрыто тестами по одной причине:
 * здесь легко ошибиться на единицу и разойтись с оценкой длительности, которую видит
 * игрок, — а расходятся они молча.
 */
public final class StepPacer {

    private double accumulator;

    /** Сбрасывает отсчёт: старт постройки, смена слоя, перемотка. */
    public void reset() {
        accumulator = 0;
    }

    /**
     * Сколько шагов приходится на очередной тик. Обычно ноль или один, но при большом
     * ускорении — несколько.
     *
     * @param ticksPerStep    тиков между шагами; ноль и меньше означают «каждый тик»
     * @param speedMultiplier множитель скорости, больше нуля
     */
    public int stepsThisTick(int ticksPerStep, double speedMultiplier) {
        double period = Math.max(1, ticksPerStep);
        accumulator += Math.max(0, speedMultiplier) / period;
        int steps = (int) Math.floor(accumulator);
        accumulator -= steps;
        return steps;
    }

    /**
     * Забывает накопленный остаток. Нужно, когда очередь кончилась досрочно: иначе
     * недоиспользованная дробь дала бы лишний шаг в начале следующего слоя.
     */
    public void dropRemainder() {
        accumulator = 0;
    }
}

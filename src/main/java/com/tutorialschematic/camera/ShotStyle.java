package com.tutorialschematic.camera;

/**
 * Доктрина съёмки — одна на дорожку, применяется ко всем слоям одинаково.
 *
 * <p>Так проще, чем вычислять уникальный план на каждый слой: вы получаете несколько
 * готовых вариантов и в редакторе выбираете, какой лучше лёг на конкретный слой.
 *
 * <p>Подъём камеры ограничен сверху намеренно. Геометрически лучший ракурс почти всегда
 * зенит — сверху ничто ничего не заслоняет, — но на видео это выглядит мёртво. Поэтому
 * ищем лучшую видимость <b>внутри операторского диапазона</b>, а не вообще.
 */
public enum ShotStyle {

    FAR_HOLD("Дальний · статичный", 1.45, 18, 34, false),
    MID_HOLD("Средний · статичный", 1.10, 14, 30, false),
    NEAR_HOLD("Ближний · статичный", 0.75, 10, 26, false),
    FAR_FLY("Дальний · пролёт", 1.45, 18, 34, true),
    MID_FLY("Средний · пролёт", 1.10, 14, 30, true);

    private final String displayName;
    /** Запас кадра: меньше единицы — кадр плотнее объекта, часть его уходит за края. */
    private final double margin;
    private final double minElevation;
    private final double maxElevation;
    private final boolean moving;

    ShotStyle(String displayName, double margin, double minElevation, double maxElevation, boolean moving) {
        this.displayName = displayName;
        this.margin = margin;
        this.minElevation = minElevation;
        this.maxElevation = maxElevation;
        this.moving = moving;
    }

    public String displayName() {
        return displayName;
    }

    public double margin() {
        return margin;
    }

    public double minElevation() {
        return minElevation;
    }

    public double maxElevation() {
        return maxElevation;
    }

    /** Пролётная доктрина ставит два кадра на слой — в начале и в конце — и едет между ними. */
    public boolean moving() {
        return moving;
    }

    /** Цвет дорожки в редакторе Flashback: статичные холоднее, пролёты теплее. */
    public int trackColour() {
        return switch (this) {
            case FAR_HOLD -> 0x4A90D9;
            case MID_HOLD -> 0x50B0A0;
            case NEAR_HOLD -> 0x7FD88F;
            case FAR_FLY -> 0xD9A24A;
            case MID_FLY -> 0xD96E4A;
        };
    }
}

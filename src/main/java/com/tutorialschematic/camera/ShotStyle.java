package com.tutorialschematic.camera;

/**
 * Доктрина съёмки — одна на дорожку, применяется ко всем слоям одинаково.
 *
 * <p>Каждая задаёт три вещи: насколько близко стоять, с какой высоты смотреть и как
 * двигаться. Дорожек намеренно много и они заметно разные: смысл не в том, чтобы мод
 * угадал единственно верный ракурс, а в том, чтобы дать выбор из непохожих вариантов.
 *
 * <p>Подъём везде ограничен сверху. Геометрически лучший ракурс почти всегда зенит —
 * оттуда ничто ничего не заслоняет, — но снятое сверху выглядит мёртво.
 */
public enum ShotStyle {

    FAR_HOLD("Дальний · статичный", 1.45, 16, 30, Movement.NONE, 0x4A90D9),
    MID_HOLD("Средний · статичный", 1.10, 14, 28, Movement.NONE, 0x50B0A0),
    NEAR_HOLD("Ближний · статичный", 0.75, 10, 24, Movement.NONE, 0x7FD88F),

    /**
     * Общий план на всю постройку целиком, один на всю запись. Не переставляется между
     * слоями: к нему возвращаются, чтобы увидеть, насколько дом вырос.
     */
    MASTER("Общий · вся постройка", 1.30, 20, 34, Movement.NONE, 0x9B7FD8),
    /** Сверху-сбоку: видно планировку и как слой ложится на предыдущие. */
    OVERVIEW_HIGH("Обзорный · сверху", 1.30, 38, 52, Movement.NONE, 0x6A8FB0),

    FAR_ARC("Дальний · дуга", 1.45, 16, 30, Movement.ARC, 0xD9A24A),
    MID_ARC("Средний · дуга", 1.10, 14, 28, Movement.ARC, 0xD96E4A),
    /** Полный неспешный облёт вокруг слоя. */
    ORBIT("Облёт кругом", 1.35, 18, 32, Movement.ORBIT, 0xD94A9B),
    /** Наезд: начинаем дальше, к концу слоя подходим вплотную. */
    DOLLY_IN("Наезд", 1.40, 12, 26, Movement.DOLLY_IN, 0xC9C24A),
    /** Отъезд: от подробности к общему плану. */
    DOLLY_OUT("Отъезд", 0.80, 12, 26, Movement.DOLLY_OUT, 0x4AC9A8);

    /** Как камера ведёт себя за время слоя. */
    public enum Movement {
        /** Стоит на месте, на следующем слое режется насухо. */
        NONE,
        /** Небольшая дуга вокруг слоя. */
        ARC,
        /** Полный круг. */
        ORBIT,
        /** Приближается. */
        DOLLY_IN,
        /** Отдаляется. */
        DOLLY_OUT
    }

    private final String displayName;
    private final double margin;
    private final double minElevation;
    private final double maxElevation;
    private final Movement movement;
    private final int trackColour;

    ShotStyle(String displayName, double margin, double minElevation, double maxElevation,
              Movement movement, int trackColour) {
        this.displayName = displayName;
        this.margin = margin;
        this.minElevation = minElevation;
        this.maxElevation = maxElevation;
        this.movement = movement;
        this.trackColour = trackColour;
    }

    public String displayName() {
        return displayName;
    }

    /** Запас кадра: меньше единицы — объект не влезает целиком, кадр плотнее. */
    public double margin() {
        return margin;
    }

    public double minElevation() {
        return minElevation;
    }

    public double maxElevation() {
        return maxElevation;
    }

    public Movement movement() {
        return movement;
    }

    public boolean moving() {
        return movement != Movement.NONE;
    }

    public int trackColour() {
        return trackColour;
    }

    /**
     * Кадрируется ли доктрина по всей постройке разом, а не по текущему слою.
     *
     * <p>У такой дорожки один кадр на всю запись: камера стоит неподвижно и показывает,
     * как дом растёт целиком. Остальные дорожки перекадровываются на каждом слое.
     */
    public boolean wholeBuild() {
        return this == MASTER;
    }

    /** Насколько градусов уводит камеру за слой. Для неподвижных — ноль. */
    public double arcDegrees() {
        return switch (movement) {
            case ARC -> 40;
            case ORBIT -> 360;
            default -> 0;
        };
    }

    /** Во сколько раз меняется расстояние за слой: меньше единицы — наезд. */
    public double distanceRatio() {
        return switch (movement) {
            case DOLLY_IN -> 0.55;
            case DOLLY_OUT -> 1.9;
            default -> 1.0;
        };
    }
}

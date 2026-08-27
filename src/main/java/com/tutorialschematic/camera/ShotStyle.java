package com.tutorialschematic.camera;

/**
 * Доктрина съёмки — одна на дорожку, применяется ко всем слоям одинаково.
 *
 * <p>Каждая задаёт четыре вещи: насколько близко стоять, с какой высоты смотреть, как
 * двигаться и <b>куда целиться</b>. Последнее оказалось важнее всего: камера, нацеленная
 * в геометрический центр слоя, показывает работу в центре кадра только в середине слоя,
 * а к концу фронт уезжает к краю. Поэтому почти все доктрины ведут работу прицелом.
 *
 * <p>Дорожек намеренно много и они заметно разные: смысл не в том, чтобы мод угадал
 * единственно верный ракурс, а в том, чтобы дать выбор из непохожих вариантов.
 *
 * <p>Подъём везде ограничен сверху. Геометрически лучший ракурс почти всегда зенит —
 * оттуда ничто ничего не заслоняет, — но снятое сверху выглядит мёртво.
 */
public enum ShotStyle {

    /**
     * Общий план на всю постройку, один на всю запись. Не переставляется между слоями:
     * к нему возвращаются, чтобы увидеть, насколько дом вырос.
     */
    MASTER("Общий · вся постройка", 1.30, 20, 34, Movement.NONE, 0x9B7FD8, 1.0, false),

    // --- Статичные: камера не двигается совсем, кадр держится до следующего слоя ---

    FAR_HOLD("Дальний · статичный", 1.50, 16, 30, Movement.NONE, 0x4A90D9, 1.0, false),
    MID_HOLD("Средний · статичный", 1.15, 14, 28, Movement.NONE, 0x50B0A0, 1.0, false),
    NEAR_HOLD("Ближний · статичный", 0.90, 12, 26, Movement.NONE, 0x7FD88F, 1.0, false),
    HIGH_HOLD("Обзорный · статичный", 1.30, 38, 52, Movement.NONE, 0x6A8FB0, 1.0, false),
    /** Тот же слой с другой стороны: даёт вторую точку для склейки без движения. */
    SIDE_HOLD("Сбоку · статичный", 1.20, 14, 28, Movement.NONE, 0xB0A06A, 1.0, false),

    // --- Ведущие: прицел идёт за работой ---

    FAR_FOLLOW("Дальний · ведёт", 1.45, 16, 30, Movement.NONE, 0x50B0A0, 0.5, false),
    MID_FOLLOW("Средний · ведёт", 1.05, 14, 28, Movement.NONE, 0xC9C24A, 0.0, false),
    NEAR_FOLLOW("Ближний · ведёт", 0.85, 12, 26, Movement.NONE, 0xD9A24A, 0.0, true),
    HIGH_FOLLOW("Обзорный · ведёт", 1.30, 38, 52, Movement.NONE, 0x8FB06A, 0.4, false),

    // --- С движением камеры ---

    MID_ARC("Средний · дуга", 1.10, 14, 28, Movement.ARC, 0xD96E4A, 0.0, false),
    ORBIT("Облёт кругом", 1.35, 18, 32, Movement.ORBIT, 0xD94A9B, 0.4, false),
    DOLLY_IN("Наезд", 1.40, 12, 26, Movement.DOLLY_IN, 0xC94A4A, 0.0, false),
    DOLLY_OUT("Отъезд", 0.80, 12, 26, Movement.DOLLY_OUT, 0x4AC9A8, 0.0, false);

    /** Как камера ведёт себя за время слоя, помимо ведения прицелом. */
    public enum Movement {
        /** Никуда не едет сама — только следует за работой, если доктрина ведёт. */
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
    private final double contextBlend;
    private final boolean frameOnFront;

    ShotStyle(String displayName, double margin, double minElevation, double maxElevation,
              Movement movement, int trackColour, double contextBlend, boolean frameOnFront) {
        this.displayName = displayName;
        this.margin = margin;
        this.minElevation = minElevation;
        this.maxElevation = maxElevation;
        this.movement = movement;
        this.trackColour = trackColour;
        this.contextBlend = contextBlend;
        this.frameOnFront = frameOnFront;
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
     * Куда целиться: 0 — точно в работу, 1 — в центр слоя целиком.
     *
     * <p>Промежуточные значения дают компромисс: работа заметно ближе к центру кадра,
     * но слой при этом не выезжает за края.
     */
    public double contextBlend() {
        return contextBlend;
    }

    /** Считать крупность по текущему фронту, а не по всему слою. */
    public boolean frameOnFront() {
        return frameOnFront;
    }

    /**
     * Ведёт ли камера работу.
     *
     * <p>Статичные доктрины не ведут намеренно: неподвижный кадр нужен для склейки и для
     * того, чтобы глазу было за что зацепиться. Работа в нём всё равно видна — просто
     * ходит внутри кадра, а не сидит ровно в центре.
     */
    public boolean follows() {
        return contextBlend < 1.0;
    }

    /**
     * Насколько отвернуть от ракурса предыдущей статичной дорожки.
     *
     * <p>Иначе все статичные планы встанут в одну точку: правила композиции у них
     * одинаковые, а разворот считается только между слоями одной дорожки.
     */
    public double sideOffset() {
        return this == SIDE_HOLD ? 90 : 0;
    }

    /**
     * Кадрируется ли доктрина по всей постройке разом, а не по текущему слою.
     *
     * <p>У такой дорожки один кадр на всю запись: камера стоит неподвижно и показывает,
     * как дом растёт целиком.
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

    /** Во сколько раз меняется расстояние к концу слоя: меньше единицы — наезд. */
    public double distanceRatio() {
        return switch (movement) {
            case DOLLY_IN -> 0.55;
            case DOLLY_OUT -> 1.9;
            default -> 1.0;
        };
    }

    /** То же, но на середине пути: наезд должен идти плавно, а не прыгать в конце. */
    public double distanceRatio(double progress) {
        return 1.0 + (distanceRatio() - 1.0) * Math.max(0, Math.min(1, progress));
    }
}

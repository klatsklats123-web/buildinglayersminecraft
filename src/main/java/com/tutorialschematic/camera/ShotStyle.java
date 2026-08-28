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
    MASTER("Общий · вся постройка", 1.30, 20, 34, Movement.NONE, 0x9B7FD8, 1.0, false, 0, true, Granularity.PREFER_WHOLE),

    // --- Статичные. Расходятся по сторонам постройки: каждая следующая ищет ракурс
    // подальше от уже занятых, иначе все встают в одну точку и отличаются только дальностью.

    MID_HOLD("Средний · статичный", 1.15, 14, 28, Movement.NONE, 0x50B0A0, 1.0, false, 1, true, Granularity.PREFER_WHOLE),
    FAR_HOLD("Дальний · статичный", 1.50, 16, 30, Movement.NONE, 0x4A90D9, 1.0, false, 1, true, Granularity.PREFER_WHOLE),
    NEAR_HOLD("Ближний · статичный", 0.90, 12, 26, Movement.NONE, 0x7FD88F, 1.0, false, 1, true, Granularity.ALWAYS_CLUSTER),
    SIDE_HOLD("Средний · другая сторона", 1.15, 14, 28, Movement.NONE, 0xB0A06A, 1.0, false, 1, false, Granularity.PREFER_WHOLE),
    FAR_SIDE_HOLD("Дальний · другая сторона", 1.50, 16, 30, Movement.NONE, 0x6A90B0, 1.0, false, 1, false, Granularity.PREFER_WHOLE),
    NEAR_SIDE_HOLD("Ближний · другая сторона", 0.90, 12, 26, Movement.NONE, 0x8FD8A8, 1.0, false, 1, false, Granularity.ALWAYS_CLUSTER),
    HIGH_HOLD("Обзорный · статичный", 1.30, 38, 52, Movement.NONE, 0x6A8FB0, 1.0, false, 1, false, Granularity.PREFER_WHOLE),

    // --- Ведущие: прицел идёт за работой. Расходятся между собой отдельной группой.

    MID_FOLLOW("Средний · ведёт", 1.05, 14, 28, Movement.NONE, 0xC9C24A, 0.0, false, 2, true, Granularity.PREFER_WHOLE),
    NEAR_FOLLOW("Ближний · ведёт", 0.85, 12, 26, Movement.NONE, 0xD9A24A, 0.0, true, 2, false, Granularity.ALWAYS_CLUSTER),
    FAR_FOLLOW("Дальний · ведёт", 1.45, 16, 30, Movement.NONE, 0x50B0A0, 0.5, false, 2, false, Granularity.PREFER_WHOLE),
    HIGH_FOLLOW("Обзорный · ведёт", 1.30, 38, 52, Movement.NONE, 0x8FB06A, 0.4, false, 2, false, Granularity.PREFER_WHOLE),

    // --- С движением камеры. Эти и так уезжают, разводить их незачем.

    MID_ARC("Средний · дуга", 1.10, 14, 28, Movement.ARC, 0xD96E4A, 0.0, false, 0, false, Granularity.PREFER_WHOLE),
    ORBIT("Облёт кругом", 1.35, 18, 32, Movement.ORBIT, 0xD94A9B, 0.4, false, 0, false, Granularity.PREFER_WHOLE),
    DOLLY_IN("Наезд", 1.40, 12, 26, Movement.DOLLY_IN, 0xC94A4A, 0.0, false, 0, false, Granularity.PREFER_WHOLE),
    DOLLY_OUT("Отъезд", 0.80, 12, 26, Movement.DOLLY_OUT, 0x4AC9A8, 0.0, false, 0, false, Granularity.PREFER_WHOLE);

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

    /**
     * Насколько охотно дорожка дробит слой на отдельные объекты вместо одного кадра.
     *
     * <p>Раньше это было одно правило на всех: «видно достаточно — один кадр, иначе режем».
     * Но одна и та же постройка (скажем, три стены анфас) для дальнего плана — законный
     * один кадр, а для ближнего — три отдельных, даже если формально с текущей точки
     * ближнего плана тоже «видно достаточно». Гранулярность — свойство самой дорожки,
     * а не общий порог видимости.
     */
    public enum Granularity {
        /** Сначала пробуем весь слой одним кадром; дробим, только если и правда не видно. */
        PREFER_WHOLE,
        /** Всегда дробим по объектам — крупный план по своей природе не вмещает всё разом. */
        ALWAYS_CLUSTER
    }

    private final String displayName;
    private final double margin;
    private final double minElevation;
    private final double maxElevation;
    private final Movement movement;
    private final int trackColour;
    private final double contextBlend;
    private final boolean frameOnFront;
    private final int spreadGroup;
    private final boolean exported;
    private final Granularity granularity;

    ShotStyle(String displayName, double margin, double minElevation, double maxElevation,
              Movement movement, int trackColour, double contextBlend, boolean frameOnFront,
              int spreadGroup, boolean exported, Granularity granularity) {
        this.displayName = displayName;
        this.margin = margin;
        this.minElevation = minElevation;
        this.maxElevation = maxElevation;
        this.movement = movement;
        this.trackColour = trackColour;
        this.contextBlend = contextBlend;
        this.frameOnFront = frameOnFront;
        this.spreadGroup = spreadGroup;
        this.exported = exported;
        this.granularity = granularity;
    }

    public Granularity granularity() {
        return granularity;
    }

    /**
     * Идёт ли доктрина в файл камер. Остальные остаются в коде и тестах — доктрина не
     * зависит от того, снимается она сейчас или нет, а раздутый список дорожек оказался
     * неудобен на практике: часть его дорожек стабильно даёт больше плохих ракурсов, чем
     * пользы (см. {@link ShotPlanner} и {@code CameraFramingTest} / {@code ShotPlannerTest}
     * — механика движения и высоты проверяется независимо от того, экспортируется доктрина
     * сейчас или нет).
     */
    public boolean exported() {
        return exported;
    }

    /**
     * Группа, внутри которой дорожки расходятся по сторонам постройки.
     *
     * <p>Ноль означает «не разводить». Без этого все статичные дорожки выбирают один и тот
     * же лучший ракурс — правила композиции у них общие, — и отличаются только дальностью.
     * Со стороны это выглядит как один план, снятый с трёх дистанций.
     */
    public int spreadGroup() {
        return spreadGroup;
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

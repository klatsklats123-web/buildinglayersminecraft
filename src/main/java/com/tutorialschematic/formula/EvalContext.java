package com.tutorialschematic.formula;

/**
 * Значения переменных для одного блока.
 *
 * <p>Объект переиспользуется: вызывается {@link #setBounds} один раз на слой,
 * затем {@link #setBlock} для каждого блока. Производные величины (центр, радиус,
 * угол, rand) пересчитываются внутри setBlock, так что формула их просто читает.
 *
 * <p>Все углы — в градусах. Для радианов есть отдельные функции sinr/cosr/tanr/atan2r.
 */
public final class EvalContext {

    /** Координаты блока внутри слоя, отсчёт от 0 у минимального угла. */
    public double x, y, z;
    /** Размеры слоя в блоках. */
    public double sizeX = 1, sizeY = 1, sizeZ = 1;
    /** Центр слоя в тех же координатах, что x/y/z. */
    public double cx, cy, cz;
    /** Смещение блока от центра. */
    public double dx, dy, dz;
    /** Нулевое направление для угла {@code a}: ноль — ось +X, иначе задано точкой старта. */
    private double angleOrigin;
    /** Расстояние от центра по горизонтали и в 3D. */
    public double r, r3;
    /** Угол вокруг вертикальной оси, 0..360 градусов. 0 — в сторону +X. */
    public double a;
    /** Стабильное псевдослучайное число 0..1, своё у каждого блока. */
    public double rand;
    /** Порядковый номер блока в исходном наборе и общее количество блоков. */
    public double index, count;
    /** Абсолютные координаты блока в мире. */
    public double wx, wy, wz;
    /**
     * Расстояние до блока по самой постройке, шагами от затравки. В отличие от {@code r}
     * обтекает пустоту: сквозь дырку пути нет, значит фронт идёт вокруг.
     */
    public double d;

    /** Смещение слоя в мире — прибавляется к x/y/z, чтобы получить wx/wy/wz. */
    private int originX, originY, originZ;
    /** Семя для rand и noise. Одинаковое семя даёт одинаковый порядок при каждом открытии. */
    private long seed = 0x5DEECE66DL;

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /**
     * Задаёт границы слоя. minX/minY/minZ — мировые координаты минимального угла,
     * size* — размеры в блоках (не меньше 1).
     */
    /**
     * Направление, которое считается нулевым для угла {@code a}, в градусах.
     *
     * <p>Ставится по точке старта: круговая анимация должна начинаться там, куда игрок
     * показал, а не от оси координат.
     */
    public void setAngleOrigin(double degrees) {
        this.angleOrigin = degrees;
    }

    public void setBounds(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, int blockCount) {
        this.originX = minX;
        this.originY = minY;
        this.originZ = minZ;
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        this.cx = (this.sizeX - 1) / 2.0;
        this.cy = (this.sizeY - 1) / 2.0;
        this.cz = (this.sizeZ - 1) / 2.0;
        this.count = blockCount;
    }

    /** Расстояние по постройке для текущего блока. Считается снаружи, обходом в ширину. */
    public void setStructureDistance(double distance) {
        this.d = distance;
    }

    /** Задаёт текущий блок. bx/by/bz — координаты внутри слоя, от 0. */
    public void setBlock(int bx, int by, int bz, int index) {
        this.x = bx;
        this.y = by;
        this.z = bz;
        this.index = index;
        this.wx = originX + bx;
        this.wy = originY + by;
        this.wz = originZ + bz;
        this.dx = bx - cx;
        this.dy = by - cy;
        this.dz = bz - cz;
        this.r = Math.sqrt(dx * dx + dz * dz);
        this.r3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Угол отсчитывается от точки старта, если она задана. Без этого правая кнопка по
        // превью влияла только на «d» — расстояние по постройке, — а круговая анимация всё
        // равно начиналась от оси +X, куда бы игрок ни ткнул. Выглядело так, будто выбор
        // точки старта работает через раз: на одних формулах работал, на других нет.
        double deg = Math.toDegrees(Math.atan2(dz, dx)) - angleOrigin;
        this.a = ((deg % 360.0) + 360.0) % 360.0;
        this.rand = hashUnit(seed, bx, by, bz);
    }

    /**
     * Детерминированный хэш в диапазоне [0, 1). Одни и те же аргументы всегда дают
     * одно и то же число, поэтому «случайная» анимация выглядит одинаково при
     * каждом просмотре превью и при самой постройке.
     */
    public static double hashUnit(long seed, long x, long y, long z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        h ^= z * 0x165667B19E3779F9L;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }
}

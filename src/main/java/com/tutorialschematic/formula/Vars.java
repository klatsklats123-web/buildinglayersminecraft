package com.tutorialschematic.formula;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Переменные, доступные в формулах.
 *
 * <p>Имена регистрозависимы: {@code x} — координата блока, {@code X} — размер слоя.
 * Чтобы на этом не спотыкаться, у размеров есть вторые имена {@code sx/sy/sz}.
 */
public final class Vars {

    public static final int X = 0, Y = 1, Z = 2;
    public static final int SIZE_X = 3, SIZE_Y = 4, SIZE_Z = 5;
    public static final int CX = 6, CY = 7, CZ = 8;
    public static final int DX = 9, DY = 10, DZ = 11;
    public static final int R = 12, R3 = 13, A = 14;
    public static final int RAND = 15, INDEX = 16, COUNT = 17;
    public static final int WX = 18, WY = 19, WZ = 20;

    /** Имя переменной, её id и текст для справки в редакторе. */
    public record Def(String name, int id, String help) {
    }

    private static final Map<String, Def> REGISTRY = new LinkedHashMap<>();

    private Vars() {
    }

    private static void def(String name, int id, String help) {
        REGISTRY.put(name, new Def(name, id, help));
    }

    static {
        def("x", X, "координата блока поперёк, от 0");
        def("y", Y, "высота блока внутри слоя, от 0");
        def("z", Z, "координата блока вдоль, от 0");

        def("X", SIZE_X, "размер слоя по x");
        def("Y", SIZE_Y, "размер слоя по y (высота)");
        def("Z", SIZE_Z, "размер слоя по z");
        def("sx", SIZE_X, "то же, что X");
        def("sy", SIZE_Y, "то же, что Y");
        def("sz", SIZE_Z, "то же, что Z");

        def("cx", CX, "центр слоя по x");
        def("cy", CY, "центр слоя по y");
        def("cz", CZ, "центр слоя по z");

        def("dx", DX, "смещение блока от центра по x");
        def("dy", DY, "смещение блока от центра по y");
        def("dz", DZ, "смещение блока от центра по z");

        def("r", R, "расстояние от центра по горизонтали");
        def("r3", R3, "расстояние от центра в 3D");
        def("a", A, "угол вокруг центра, 0..360 градусов");

        def("rand", RAND, "стабильное случайное число 0..1, своё у каждого блока");
        def("i", INDEX, "порядковый номер блока в исходном наборе");
        def("n", COUNT, "всего блоков в слое");

        def("wx", WX, "абсолютная координата в мире по x");
        def("wy", WY, "абсолютная координата в мире по y");
        def("wz", WZ, "абсолютная координата в мире по z");
    }

    public static Def get(String name) {
        return REGISTRY.get(name);
    }

    public static boolean exists(String name) {
        return REGISTRY.containsKey(name);
    }

    /** Все переменные в порядке объявления — для панели справки. */
    public static Map<String, Def> all() {
        return java.util.Collections.unmodifiableMap(REGISTRY);
    }

    /** Читает значение переменной по её id из контекста. */
    public static double read(int id, EvalContext c) {
        return switch (id) {
            case X -> c.x;
            case Y -> c.y;
            case Z -> c.z;
            case SIZE_X -> c.sizeX;
            case SIZE_Y -> c.sizeY;
            case SIZE_Z -> c.sizeZ;
            case CX -> c.cx;
            case CY -> c.cy;
            case CZ -> c.cz;
            case DX -> c.dx;
            case DY -> c.dy;
            case DZ -> c.dz;
            case R -> c.r;
            case R3 -> c.r3;
            case A -> c.a;
            case RAND -> c.rand;
            case INDEX -> c.index;
            case COUNT -> c.count;
            case WX -> c.wx;
            case WY -> c.wy;
            case WZ -> c.wz;
            default -> 0;
        };
    }
}

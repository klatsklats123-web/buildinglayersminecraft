package com.tutorialschematic.formula;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Набор функций, доступных в формулах.
 *
 * <p>Все углы — в градусах: {@code sin(90)} равно 1. Для радианов есть варианты
 * с суффиксом r ({@code sinr}, {@code cosr}, {@code tanr}, {@code atan2r}).
 */
public final class Functions {

    /** Реализация функции: получает уже вычисленные аргументы. */
    @FunctionalInterface
    public interface Impl {
        double apply(double[] args);
    }

    /** Описание функции: сколько аргументов принимает и что делает. */
    public record Def(String name, int minArgs, int maxArgs, Impl impl, String help) {
        public boolean acceptsArgCount(int n) {
            return n >= minArgs && n <= maxArgs;
        }

        public String signature() {
            if (maxArgs == Integer.MAX_VALUE) {
                return name + "(a, b, ...)";
            }
            if (minArgs == maxArgs) {
                StringBuilder sb = new StringBuilder(name).append('(');
                for (int i = 0; i < minArgs; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append((char) ('a' + i));
                }
                return sb.append(')').toString();
            }
            return name + "(" + minArgs + ".." + maxArgs + " арг.)";
        }
    }

    private static final Map<String, Def> REGISTRY = new LinkedHashMap<>();

    private Functions() {
    }

    private static void def(String name, int min, int max, Impl impl, String help) {
        REGISTRY.put(name, new Def(name, min, max, impl, help));
    }

    static {
        def("abs", 1, 1, a -> Math.abs(a[0]), "модуль числа");
        def("sign", 1, 1, a -> Math.signum(a[0]), "знак: -1, 0 или 1");
        def("sqrt", 1, 1, a -> Math.sqrt(Math.max(0, a[0])), "квадратный корень");
        def("floor", 1, 1, a -> Math.floor(a[0]), "округление вниз");
        def("ceil", 1, 1, a -> Math.ceil(a[0]), "округление вверх");
        def("round", 1, 1, a -> Math.round(a[0]), "округление к ближайшему");
        def("frac", 1, 1, a -> a[0] - Math.floor(a[0]), "дробная часть");

        def("min", 1, Integer.MAX_VALUE, a -> {
            double m = a[0];
            for (int i = 1; i < a.length; i++) m = Math.min(m, a[i]);
            return m;
        }, "наименьшее из значений");
        def("max", 1, Integer.MAX_VALUE, a -> {
            double m = a[0];
            for (int i = 1; i < a.length; i++) m = Math.max(m, a[i]);
            return m;
        }, "наибольшее из значений");
        def("clamp", 3, 3, a -> Math.max(a[1], Math.min(a[2], a[0])), "зажать значение между границами: clamp(v, min, max)");

        def("hypot", 2, 3, a -> a.length == 2
                ? Math.sqrt(a[0] * a[0] + a[1] * a[1])
                : Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), "длина вектора");
        def("dist", 2, 3, a -> a.length == 2
                ? Math.sqrt(a[0] * a[0] + a[1] * a[1])
                : Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), "то же, что hypot");

        def("pow", 2, 2, a -> Math.pow(a[0], a[1]), "возведение в степень, то же что ^");
        def("mod", 2, 2, a -> {
            double m = a[1];
            if (m == 0) return 0;
            double v = a[0] % m;
            return v < 0 ? v + Math.abs(m) : v;
        }, "остаток от деления, всегда неотрицательный");
        def("log", 1, 1, a -> a[0] <= 0 ? 0 : Math.log(a[0]), "натуральный логарифм");
        def("log2", 1, 1, a -> a[0] <= 0 ? 0 : Math.log(a[0]) / Math.log(2), "логарифм по основанию 2");
        def("log10", 1, 1, a -> a[0] <= 0 ? 0 : Math.log10(a[0]), "логарифм по основанию 10");
        def("exp", 1, 1, a -> Math.exp(a[0]), "экспонента");

        def("sin", 1, 1, a -> Math.sin(Math.toRadians(a[0])), "синус, аргумент в градусах");
        def("cos", 1, 1, a -> Math.cos(Math.toRadians(a[0])), "косинус, аргумент в градусах");
        def("tan", 1, 1, a -> Math.tan(Math.toRadians(a[0])), "тангенс, аргумент в градусах");
        def("asin", 1, 1, a -> Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, a[0])))), "арксинус, результат в градусах");
        def("acos", 1, 1, a -> Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a[0])))), "арккосинус, результат в градусах");
        def("atan", 1, 1, a -> Math.toDegrees(Math.atan(a[0])), "арктангенс, результат в градусах");
        def("atan2", 2, 2, a -> {
            double d = Math.toDegrees(Math.atan2(a[0], a[1]));
            return d < 0 ? d + 360 : d;
        }, "угол точки, 0..360 градусов: atan2(dz, dx)");

        def("sinr", 1, 1, a -> Math.sin(a[0]), "синус, аргумент в радианах");
        def("cosr", 1, 1, a -> Math.cos(a[0]), "косинус, аргумент в радианах");
        def("tanr", 1, 1, a -> Math.tan(a[0]), "тангенс, аргумент в радианах");
        def("atan2r", 2, 2, a -> Math.atan2(a[0], a[1]), "угол точки в радианах");
        def("deg", 1, 1, a -> Math.toDegrees(a[0]), "радианы в градусы");
        def("rad", 1, 1, a -> Math.toRadians(a[0]), "градусы в радианы");

        def("lerp", 3, 3, a -> a[0] + (a[1] - a[0]) * a[2], "плавный переход: lerp(от, до, доля)");
        def("step", 2, 2, a -> a[1] < a[0] ? 0 : 1, "0 пока v меньше границы, дальше 1: step(граница, v)");
        def("smoothstep", 3, 3, a -> {
            double e0 = a[0], e1 = a[1];
            if (e0 == e1) return a[2] < e0 ? 0 : 1;
            double t = Math.max(0, Math.min(1, (a[2] - e0) / (e1 - e0)));
            return t * t * (3 - 2 * t);
        }, "плавная ступенька от 0 до 1");
        def("if", 3, 3, a -> a[0] != 0 ? a[1] : a[2], "то же, что условие ? да : нет");

        def("hash", 1, 3, a -> EvalContext.hashUnit(
                0x9E3779B9L,
                Double.doubleToLongBits(a[0]),
                a.length > 1 ? Double.doubleToLongBits(a[1]) : 0,
                a.length > 2 ? Double.doubleToLongBits(a[2]) : 0
        ), "стабильное псевдослучайное число 0..1 от аргументов");

        def("noise", 1, 3, a -> noise(
                a[0],
                a.length > 1 ? a[1] : 0,
                a.length > 2 ? a[2] : 0
        ), "плавный шум 0..1, для органичных «рваных» переходов");
    }

    public static Def get(String name) {
        return REGISTRY.get(name);
    }

    public static boolean exists(String name) {
        return REGISTRY.containsKey(name);
    }

    /** Все функции в порядке объявления — для справки в редакторе. */
    public static Map<String, Def> all() {
        return java.util.Collections.unmodifiableMap(REGISTRY);
    }

    /**
     * Гладкий value-noise на целочисленной решётке: значения в узлах берутся из
     * детерминированного хэша и смешиваются кубической интерполяцией.
     */
    private static double noise(double x, double y, double z) {
        long xi = (long) Math.floor(x), yi = (long) Math.floor(y), zi = (long) Math.floor(z);
        double xf = fade(x - xi), yf = fade(y - yi), zf = fade(z - zi);

        double c000 = EvalContext.hashUnit(1L, xi, yi, zi);
        double c100 = EvalContext.hashUnit(1L, xi + 1, yi, zi);
        double c010 = EvalContext.hashUnit(1L, xi, yi + 1, zi);
        double c110 = EvalContext.hashUnit(1L, xi + 1, yi + 1, zi);
        double c001 = EvalContext.hashUnit(1L, xi, yi, zi + 1);
        double c101 = EvalContext.hashUnit(1L, xi + 1, yi, zi + 1);
        double c011 = EvalContext.hashUnit(1L, xi, yi + 1, zi + 1);
        double c111 = EvalContext.hashUnit(1L, xi + 1, yi + 1, zi + 1);

        double x00 = mix(c000, c100, xf), x10 = mix(c010, c110, xf);
        double x01 = mix(c001, c101, xf), x11 = mix(c011, c111, xf);
        return mix(mix(x00, x10, yf), mix(x01, x11, yf), zf);
    }

    private static double fade(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }
}

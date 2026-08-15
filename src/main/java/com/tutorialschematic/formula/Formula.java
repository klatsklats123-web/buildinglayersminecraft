package com.tutorialschematic.formula;

/**
 * Скомпилированная формула.
 *
 * <p>Строка разбирается один раз через {@link #compile}, дальше {@link #eval} вызывается
 * для каждого блока — это горячий путь, поэтому дерево узлов держим примитивным.
 *
 * <pre>{@code
 * Formula f = Formula.compile("y*2 + r");
 * ctx.setBlock(3, 1, 4, 0);
 * double key = f.eval(ctx);
 * }</pre>
 */
public final class Formula {

    /** Узел дерева выражения. */
    public interface Node {
        double eval(EvalContext c);
    }

    record Const(double value) implements Node {
        @Override
        public double eval(EvalContext c) {
            return value;
        }
    }

    record VarRef(int id) implements Node {
        @Override
        public double eval(EvalContext c) {
            return Vars.read(id, c);
        }
    }

    record Neg(Node a) implements Node {
        @Override
        public double eval(EvalContext c) {
            return -a.eval(c);
        }
    }

    record Not(Node a) implements Node {
        @Override
        public double eval(EvalContext c) {
            return a.eval(c) == 0 ? 1 : 0;
        }
    }

    /** Бинарная операция; {@code op} — код из констант ниже. */
    record Binary(int op, Node l, Node r) implements Node {
        @Override
        public double eval(EvalContext c) {
            double a = l.eval(c);
            // && и || вычисляют правую часть только при необходимости
            if (op == AND) return (a != 0 && r.eval(c) != 0) ? 1 : 0;
            if (op == OR) return (a != 0 || r.eval(c) != 0) ? 1 : 0;
            double b = r.eval(c);
            return switch (op) {
                case ADD -> a + b;
                case SUB -> a - b;
                case MUL -> a * b;
                case DIV -> b == 0 ? 0 : a / b;
                case REM -> b == 0 ? 0 : a % b;
                case POW -> Math.pow(a, b);
                case LT -> a < b ? 1 : 0;
                case LE -> a <= b ? 1 : 0;
                case GT -> a > b ? 1 : 0;
                case GE -> a >= b ? 1 : 0;
                case EQ -> a == b ? 1 : 0;
                case NE -> a != b ? 1 : 0;
                default -> 0;
            };
        }
    }

    record Ternary(Node cond, Node ifTrue, Node ifFalse) implements Node {
        @Override
        public double eval(EvalContext c) {
            return cond.eval(c) != 0 ? ifTrue.eval(c) : ifFalse.eval(c);
        }
    }

    record Call(Functions.Def def, Node[] args) implements Node {
        @Override
        public double eval(EvalContext c) {
            double[] v = new double[args.length];
            for (int i = 0; i < args.length; i++) {
                v[i] = args[i].eval(c);
            }
            return def.impl().apply(v);
        }
    }

    static final int ADD = 1, SUB = 2, MUL = 3, DIV = 4, REM = 5, POW = 6;
    static final int LT = 7, LE = 8, GT = 9, GE = 10, EQ = 11, NE = 12;
    static final int AND = 13, OR = 14;

    private final String source;
    private final Node root;
    private final boolean usesVariables;

    Formula(String source, Node root, boolean usesVariables) {
        this.source = source;
        this.root = root;
        this.usesVariables = usesVariables;
    }

    /**
     * Разбирает строку. Бросает {@link FormulaException} с позицией ошибки,
     * если строка некорректна.
     */
    public static Formula compile(String source) {
        return new FormulaParser(source).parseFormula();
    }

    /** Разбирает строку, возвращая {@code null} вместо исключения. */
    public static Formula compileOrNull(String source) {
        try {
            return compile(source);
        } catch (FormulaException e) {
            return null;
        }
    }

    /** Проверяет строку и возвращает текст ошибки, либо {@code null}, если всё в порядке. */
    public static String validate(String source) {
        try {
            compile(source);
            return null;
        } catch (FormulaException e) {
            return e.getMessage();
        }
    }

    public double eval(EvalContext c) {
        return root.eval(c);
    }

    public String source() {
        return source;
    }

    /**
     * {@code false}, если формула не зависит ни от одной переменной — такая формула
     * даёт всем блокам один и тот же ключ и ничего не сортирует. Редактор это подсвечивает.
     */
    public boolean usesVariables() {
        return usesVariables;
    }

    @Override
    public String toString() {
        return source;
    }
}

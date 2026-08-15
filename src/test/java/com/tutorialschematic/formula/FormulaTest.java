package com.tutorialschematic.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaTest {

    /** Контекст слоя 5x3x5 с блоком в указанной точке. */
    private static EvalContext ctx(int x, int y, int z) {
        EvalContext c = new EvalContext();
        c.setBounds(0, 0, 0, 5, 3, 5, 75);
        c.setBlock(x, y, z, 0);
        return c;
    }

    private static double eval(String source, EvalContext c) {
        return Formula.compile(source).eval(c);
    }

    @Test
    void arithmeticFollowsPrecedence() {
        EvalContext c = ctx(0, 0, 0);
        assertEquals(7, eval("1 + 2 * 3", c));
        assertEquals(9, eval("(1 + 2) * 3", c));
        assertEquals(-8, eval("-2^3", c), "унарный минус применяется к результату степени");
        assertEquals(0.5, eval("2^-1", c), "показатель может быть отрицательным");
        assertEquals(2, eval("8 / 2 / 2", c), "деление левоассоциативно");
    }

    @Test
    void divisionByZeroYieldsZeroInsteadOfInfinity() {
        // Бесконечность и NaN сломали бы сортировку, поэтому деление на ноль даёт 0
        EvalContext c = ctx(0, 0, 0);
        assertEquals(0, eval("5 / 0", c));
        assertEquals(0, eval("5 % 0", c));
    }

    @Test
    void variablesReflectBlockPosition() {
        EvalContext c = ctx(1, 2, 3);
        assertEquals(1, eval("x", c));
        assertEquals(2, eval("y", c));
        assertEquals(3, eval("z", c));
        assertEquals(5, eval("X", c));
        assertEquals(5, eval("sx", c), "sx — второе имя для X");
        assertEquals(3, eval("Y", c));
    }

    @Test
    void derivedVariablesUseLayerCentre() {
        // Центр слоя 5x3x5 — (2, 1, 2)
        EvalContext c = ctx(4, 1, 2);
        assertEquals(2, eval("cx", c));
        assertEquals(2, eval("dx", c));
        assertEquals(0, eval("dz", c));
        assertEquals(2, eval("r", c), 1e-9);
        assertEquals(0, eval("a", c), 1e-9, "точка на +X — это угол 0");
    }

    @Test
    void angleIsNormalisedToFullTurn() {
        EvalContext c = ctx(0, 1, 2);
        assertEquals(180, eval("a", c), 1e-9, "точка на -X — это 180, а не -180");
    }

    @Test
    void trigonometryWorksInDegrees() {
        EvalContext c = ctx(0, 0, 0);
        assertEquals(1, eval("sin(90)", c), 1e-9);
        assertEquals(0, eval("cos(90)", c), 1e-9);
        assertEquals(1, eval("sinr(rad(90))", c), 1e-9, "для радианов есть отдельные функции");
    }

    @Test
    void comparisonsReturnOneOrZeroSoTheyCanBeCombined() {
        EvalContext c = ctx(3, 0, 0);
        assertEquals(1, eval("x > 2", c));
        assertEquals(0, eval("x > 5", c));
        assertEquals(103, eval("(x > 2) * 100 + x", c), "так задаётся приоритет внутри одной формулы");
    }

    @Test
    void ternaryPicksBranch() {
        assertEquals(4, eval("mod(z,2)==0 ? x : X-1-x", ctx(4, 0, 0)));
        assertEquals(0, eval("mod(z,2)==0 ? x : X-1-x", ctx(4, 0, 1)), "в нечётном ряду направление обратное");
    }

    @Test
    void logicalOperatorsShortCircuit() {
        EvalContext c = ctx(0, 0, 0);
        assertEquals(0, eval("false && 1/0", c));
        assertEquals(1, eval("true || 1/0", c));
    }

    @Test
    void randIsStableForTheSameBlockAndSeed() {
        EvalContext a = ctx(2, 1, 3);
        EvalContext b = ctx(2, 1, 3);
        assertEquals(eval("rand", a), eval("rand", b), "иначе превью и постройка разошлись бы");

        EvalContext other = ctx(2, 1, 4);
        assertTrue(eval("rand", a) != eval("rand", other), "у разных блоков числа разные");

        EvalContext reseeded = ctx(2, 1, 3);
        reseeded.setSeed(999);
        reseeded.setBlock(2, 1, 3, 0);
        assertTrue(eval("rand", a) != eval("rand", reseeded), "смена семени меняет порядок");
    }

    @Test
    void randStaysInUnitRange() {
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                double v = eval("rand", ctx(x, 0, z));
                assertTrue(v >= 0 && v < 1, "rand вне диапазона: " + v);
            }
        }
    }

    @Test
    void noiseStaysInUnitRangeAndIsSmooth() {
        EvalContext c = ctx(0, 0, 0);
        double a = eval("noise(1.0, 0, 0)", c);
        double b = eval("noise(1.01, 0, 0)", c);
        assertTrue(a >= 0 && a <= 1);
        assertTrue(Math.abs(a - b) < 0.1, "близкие точки должны давать близкие значения");
    }

    @Test
    void constantFormulaIsDetected() {
        assertFalse(Formula.compile("42").usesVariables(), "такая формула ничего не сортирует");
        assertTrue(Formula.compile("y * 2").usesVariables());
    }

    @Test
    void parseErrorsCarryPositionAndMessage() {
        FormulaException e = assertThrows(FormulaException.class, () -> Formula.compile("min(x, X-x"));
        assertTrue(e.position() >= 0);
        assertTrue(e.describe().contains("^"), "описание должно указывать на место ошибки");

        assertThrows(FormulaException.class, () -> Formula.compile(""));
        assertThrows(FormulaException.class, () -> Formula.compile("x +"));
        assertThrows(FormulaException.class, () -> Formula.compile("2 @ 3"));
    }

    @Test
    void unknownNameSuggestsTheClosestMatch() {
        FormulaException e = assertThrows(FormulaException.class, () -> Formula.compile("rnd"));
        assertTrue(e.getMessage().contains("rand"), "ожидалась подсказка про rand, а было: " + e.getMessage());
    }

    @Test
    void wrongArgumentCountIsReported() {
        FormulaException e = assertThrows(FormulaException.class, () -> Formula.compile("clamp(x, 1)"));
        assertTrue(e.getMessage().contains("clamp"));
    }

    @Test
    void functionWithoutParenthesesIsExplained() {
        FormulaException e = assertThrows(FormulaException.class, () -> Formula.compile("sqrt"));
        assertTrue(e.getMessage().contains("скобки"));
    }

    @Test
    void singleEqualsIsCalledOut() {
        FormulaException e = assertThrows(FormulaException.class, () -> Formula.compile("x = 1"));
        assertTrue(e.getMessage().contains("=="));
    }

    @Test
    void validateReportsNullWhenFine() {
        assertNull(Formula.validate("y + r"));
        assertNotNull(Formula.validate("y +"));
    }

    @Test
    void scientificNotationDoesNotClashWithConstantE() {
        EvalContext c = ctx(0, 0, 0);
        assertEquals(1000, eval("1e3", c));
        assertEquals(Math.E, eval("e", c), 1e-9);
        assertEquals(Math.E * 2, eval("2*e", c), 1e-9);
    }

    @Test
    void everyPresetFormulaCompiles() {
        for (com.tutorialschematic.order.OrderPresets.Preset preset : com.tutorialschematic.order.OrderPresets.all()) {
            for (String formula : preset.formulas()) {
                assertNull(Formula.validate(formula),
                        "пресет «" + preset.name() + "» содержит нерабочую формулу: " + formula);
            }
        }
    }
}

package com.tutorialschematic.formula;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор формулы методом рекурсивного спуска.
 *
 * <p>Приоритеты, от низшего к высшему:
 * <pre>
 *   ?:        тернарный выбор
 *   ||        или
 *   &amp;&amp;        и
 *   == !=     равенство
 *   &lt; &lt;= &gt; &gt;=  сравнение
 *   + -       сложение
 *   * / %     умножение
 *   - + !     унарные
 *   ^         степень (правоассоциативная)
 * </pre>
 *
 * <p>Сравнения дают 1 или 0, поэтому их можно свободно складывать и умножать:
 * {@code (y > 4) * 100 + x} поднимает всё выше четвёртого ряда в конец очереди.
 */
final class FormulaParser {

    private final String src;
    private int pos;
    private boolean sawVariable;

    FormulaParser(String source) {
        this.src = source == null ? "" : source;
    }

    Formula parseFormula() {
        skipSpace();
        if (pos >= src.length()) {
            throw error("формула пустая", pos);
        }
        Formula.Node node = parseTernary();
        skipSpace();
        if (pos < src.length()) {
            throw error("лишний символ '" + src.charAt(pos) + "'", pos);
        }
        return new Formula(src, node, sawVariable);
    }

    // ---- уровни приоритета ----

    private Formula.Node parseTernary() {
        Formula.Node cond = parseOr();
        skipSpace();
        if (!eat('?')) {
            return cond;
        }
        Formula.Node ifTrue = parseTernary();
        skipSpace();
        if (!eat(':')) {
            throw error("после '?' ожидалось ':' — пишется так: условие ? да : нет", pos);
        }
        Formula.Node ifFalse = parseTernary();
        return new Formula.Ternary(cond, ifTrue, ifFalse);
    }

    private Formula.Node parseOr() {
        Formula.Node left = parseAnd();
        while (true) {
            skipSpace();
            if (eatSeq("||")) {
                left = new Formula.Binary(Formula.OR, left, parseAnd());
            } else {
                return left;
            }
        }
    }

    private Formula.Node parseAnd() {
        Formula.Node left = parseEquality();
        while (true) {
            skipSpace();
            if (eatSeq("&&")) {
                left = new Formula.Binary(Formula.AND, left, parseEquality());
            } else {
                return left;
            }
        }
    }

    private Formula.Node parseEquality() {
        Formula.Node left = parseComparison();
        while (true) {
            skipSpace();
            if (eatSeq("==")) {
                left = new Formula.Binary(Formula.EQ, left, parseComparison());
            } else if (eatSeq("!=")) {
                left = new Formula.Binary(Formula.NE, left, parseComparison());
            } else if (peek() == '=' && peek(1) != '=') {
                throw error("для сравнения нужно двойное '==' ", pos);
            } else {
                return left;
            }
        }
    }

    private Formula.Node parseComparison() {
        Formula.Node left = parseAdditive();
        while (true) {
            skipSpace();
            if (eatSeq("<=")) {
                left = new Formula.Binary(Formula.LE, left, parseAdditive());
            } else if (eatSeq(">=")) {
                left = new Formula.Binary(Formula.GE, left, parseAdditive());
            } else if (peek() == '<') {
                pos++;
                left = new Formula.Binary(Formula.LT, left, parseAdditive());
            } else if (peek() == '>') {
                pos++;
                left = new Formula.Binary(Formula.GT, left, parseAdditive());
            } else {
                return left;
            }
        }
    }

    private Formula.Node parseAdditive() {
        Formula.Node left = parseMultiplicative();
        while (true) {
            skipSpace();
            char c = peek();
            if (c == '+') {
                pos++;
                left = new Formula.Binary(Formula.ADD, left, parseMultiplicative());
            } else if (c == '-') {
                pos++;
                left = new Formula.Binary(Formula.SUB, left, parseMultiplicative());
            } else {
                return left;
            }
        }
    }

    private Formula.Node parseMultiplicative() {
        Formula.Node left = parseUnary();
        while (true) {
            skipSpace();
            char c = peek();
            if (c == '*') {
                pos++;
                left = new Formula.Binary(Formula.MUL, left, parseUnary());
            } else if (c == '/') {
                pos++;
                left = new Formula.Binary(Formula.DIV, left, parseUnary());
            } else if (c == '%') {
                pos++;
                left = new Formula.Binary(Formula.REM, left, parseUnary());
            } else {
                return left;
            }
        }
    }

    private Formula.Node parseUnary() {
        skipSpace();
        char c = peek();
        if (c == '-') {
            pos++;
            return new Formula.Neg(parseUnary());
        }
        if (c == '+') {
            pos++;
            return parseUnary();
        }
        if (c == '!' && peek(1) != '=') {
            pos++;
            return new Formula.Not(parseUnary());
        }
        return parsePower();
    }

    private Formula.Node parsePower() {
        Formula.Node base = parsePrimary();
        skipSpace();
        if (eat('^')) {
            // правоассоциативная, показатель может быть отрицательным: 2^-1
            return new Formula.Binary(Formula.POW, base, parseUnary());
        }
        return base;
    }

    private Formula.Node parsePrimary() {
        skipSpace();
        if (pos >= src.length()) {
            throw error("формула обрывается — не хватает значения", pos);
        }
        char c = peek();

        if (c == '(') {
            pos++;
            Formula.Node inner = parseTernary();
            skipSpace();
            if (!eat(')')) {
                throw error("не хватает закрывающей скобки", pos);
            }
            return inner;
        }

        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            return parseNumber();
        }

        if (Character.isLetter(c) || c == '_') {
            return parseIdentifier();
        }

        throw error("непонятный символ '" + c + "'", pos);
    }

    private Formula.Node parseNumber() {
        int start = pos;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        // экспоненциальная запись 1e3 / 2.5e-4, но только если после e действительно цифры
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int save = pos;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            } else {
                pos = save;
            }
        }
        String text = src.substring(start, pos);
        try {
            return new Formula.Const(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            throw error("не удалось прочитать число '" + text + "'", start);
        }
    }

    private Formula.Node parseIdentifier() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        String name = src.substring(start, pos);

        skipSpace();
        if (peek() == '(') {
            return parseCall(name, start);
        }

        Vars.Def var = Vars.get(name);
        if (var != null) {
            sawVariable = true;
            return new Formula.VarRef(var.id());
        }

        switch (name) {
            case "pi", "PI" -> {
                return new Formula.Const(Math.PI);
            }
            case "tau", "TAU" -> {
                return new Formula.Const(Math.PI * 2);
            }
            case "e", "E" -> {
                return new Formula.Const(Math.E);
            }
            case "true" -> {
                return new Formula.Const(1);
            }
            case "false" -> {
                return new Formula.Const(0);
            }
            default -> {
            }
        }

        if (Functions.exists(name)) {
            throw error("'" + name + "' — это функция, после неё нужны скобки: " + Functions.get(name).signature(), start);
        }
        throw error("неизвестное имя '" + name + "'" + suggest(name), start);
    }

    private Formula.Node parseCall(String name, int nameStart) {
        Functions.Def def = Functions.get(name);
        if (def == null) {
            if (Vars.exists(name)) {
                throw error("'" + name + "' — это переменная, её нельзя вызывать как функцию", nameStart);
            }
            throw error("неизвестная функция '" + name + "'" + suggest(name), nameStart);
        }

        pos++; // '('
        List<Formula.Node> args = new ArrayList<>();
        skipSpace();
        if (peek() != ')') {
            while (true) {
                args.add(parseTernary());
                skipSpace();
                if (eat(',')) {
                    continue;
                }
                break;
            }
        }
        skipSpace();
        if (!eat(')')) {
            throw error("не хватает закрывающей скобки у " + name + "(", pos);
        }

        if (!def.acceptsArgCount(args.size())) {
            throw error(name + " принимает " + argCountText(def) + ", а получил " + args.size(), nameStart);
        }
        return new Formula.Call(def, args.toArray(new Formula.Node[0]));
    }

    private static String argCountText(Functions.Def def) {
        if (def.maxArgs() == Integer.MAX_VALUE) {
            return "от " + def.minArgs() + " аргументов";
        }
        if (def.minArgs() == def.maxArgs()) {
            return def.minArgs() + " арг.";
        }
        return "от " + def.minArgs() + " до " + def.maxArgs() + " арг.";
    }

    /** Подсказывает ближайшее по написанию известное имя — частая причина ошибки это опечатка. */
    private static String suggest(String name) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : Vars.all().keySet()) {
            int d = editDistance(name, candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        for (String candidate : Functions.all().keySet()) {
            int d = editDistance(name, candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        if (best != null && bestDist <= Math.max(1, name.length() / 3)) {
            return ". Может быть, '" + best + "'?";
        }
        return "";
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    // ---- работа с символами ----

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char peek(int ahead) {
        int p = pos + ahead;
        return p < src.length() ? src.charAt(p) : '\0';
    }

    private boolean eat(char c) {
        if (peek() == c) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean eatSeq(String s) {
        if (src.startsWith(s, pos)) {
            pos += s.length();
            return true;
        }
        return false;
    }

    private void skipSpace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private FormulaException error(String message, int at) {
        return new FormulaException(message, src, Math.min(at, src.length()));
    }
}

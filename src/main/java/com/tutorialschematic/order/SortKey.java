package com.tutorialschematic.order;

import com.google.gson.JsonObject;
import com.tutorialschematic.formula.Formula;
import com.tutorialschematic.formula.FormulaException;

/**
 * Один уровень сортировки: «сначала по y», «потом по a» и так далее.
 *
 * <p>Формула хранится и как текст (его редактирует пользователь и он же уходит в файл),
 * и в скомпилированном виде. Если текст не разбирается, {@link #compiled()} равно
 * {@code null}, а {@link #error()} содержит объяснение для подсветки в редакторе —
 * так сломанная формула не роняет весь экран.
 */
public final class SortKey {

    private String source;
    private boolean descending;

    private Formula compiled;
    private String error;
    private int errorPosition = -1;

    public SortKey(String source) {
        this(source, false);
    }

    public SortKey(String source, boolean descending) {
        this.descending = descending;
        setSource(source);
    }

    public String source() {
        return source;
    }

    /**
     * Меняет текст формулы и сразу пытается её разобрать.
     * Возвращает {@code true}, если формула корректна.
     */
    public boolean setSource(String newSource) {
        this.source = newSource == null ? "" : newSource;
        this.compiled = null;
        this.error = null;
        this.errorPosition = -1;
        if (this.source.isBlank()) {
            this.error = "формула пустая";
            this.errorPosition = 0;
            return false;
        }
        try {
            this.compiled = Formula.compile(this.source);
            return true;
        } catch (FormulaException e) {
            this.error = e.getMessage();
            this.errorPosition = e.position();
            return false;
        }
    }

    public boolean descending() {
        return descending;
    }

    public void setDescending(boolean descending) {
        this.descending = descending;
    }

    public void toggleDescending() {
        this.descending = !this.descending;
    }

    public Formula compiled() {
        return compiled;
    }

    public boolean isValid() {
        return compiled != null;
    }

    /** Текст ошибки разбора либо {@code null}. */
    public String error() {
        return error;
    }

    /** Позиция ошибки в строке формулы либо -1. */
    public int errorPosition() {
        return errorPosition;
    }

    /**
     * {@code true}, если формула разбирается, но не зависит ни от одной переменной.
     * Такой уровень сортировки ничего не делает — стоит предупредить.
     */
    public boolean isConstant() {
        return compiled != null && !compiled.usesVariables();
    }

    public SortKey copy() {
        return new SortKey(source, descending);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("formula", source);
        json.addProperty("descending", descending);
        return json;
    }

    public static SortKey fromJson(JsonObject json) {
        String formula = json.has("formula") ? json.get("formula").getAsString() : "y";
        boolean desc = json.has("descending") && json.get("descending").getAsBoolean();
        return new SortKey(formula, desc);
    }

    @Override
    public String toString() {
        return descending ? source + " ↓" : source;
    }
}

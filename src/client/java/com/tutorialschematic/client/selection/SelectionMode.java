package com.tutorialschematic.client.selection;

/**
 * Способы набирать блоки в слой.
 *
 * <p>Режимов намеренно мало: один точный, один для больших кусков и один по материалу.
 * Этого хватает почти всегда, а помнить нужно только три сочетания клавиш.
 *
 * <p>Кнопки везде значат одно и то же: <b>ПКМ добавляет, ЛКМ убирает</b> — как в самой игре,
 * где правой ставят блок, а левой ломают. Жест при этом одинаковый: чтобы убрать коробку,
 * её обводят теми же двумя кликами, только левой кнопкой.
 */
public enum SelectionMode {

    SINGLE("По одному блоку",
            "Точная правка: по блоку за клик",
            "ПКМ добавить блок · ЛКМ убрать блок"),

    TWO_POINTS("Две точки",
            "Большие куски: пол, стена, этаж целиком",
            "ПКМ дважды — добавить коробку · ЛКМ дважды — убрать"),

    FLOOD("Заливка",
            "Всё, что связано между собой и сделано из того же блока",
            "ПКМ залить · ЛКМ убрать залитое"),

    MATERIAL("Магнит",
            "Весь такой блок вокруг, даже если куски разделены другими",
            "ПКМ собрать всё такое · ЛКМ убрать");

    private final String displayName;
    private final String hint;
    private final String controls;

    SelectionMode(String displayName, String hint, String controls) {
        this.displayName = displayName;
        this.hint = hint;
        this.controls = controls;
    }

    public String displayName() {
        return displayName;
    }

    /** Для чего режим нужен — показывается в подсказке кнопки. */
    public String hint() {
        return hint;
    }

    /** Какие кнопки что делают — висит на панели в углу экрана, чтобы не гадать. */
    public String controls() {
        return controls;
    }

    public SelectionMode next() {
        SelectionMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}

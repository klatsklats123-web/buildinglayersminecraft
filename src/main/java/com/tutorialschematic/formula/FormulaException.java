package com.tutorialschematic.formula;

/**
 * Ошибка разбора или вычисления формулы.
 *
 * <p>Хранит позицию в исходной строке, чтобы редактор мог подчеркнуть проблемное место.
 * {@link #position()} возвращает -1, если позиция неизвестна.
 */
public class FormulaException extends RuntimeException {

    private final int position;
    private final String source;

    public FormulaException(String message, String source, int position) {
        super(message);
        this.source = source == null ? "" : source;
        this.position = position;
    }

    public FormulaException(String message) {
        this(message, "", -1);
    }

    public int position() {
        return position;
    }

    public String source() {
        return source;
    }

    /**
     * Однострочное описание с указателем на место ошибки, например:
     * <pre>
     * min(x, X-x
     *          ^ ожидалась закрывающая скобка
     * </pre>
     */
    public String describe() {
        if (position < 0 || source.isEmpty()) {
            return getMessage();
        }
        StringBuilder caret = new StringBuilder();
        for (int i = 0; i < Math.min(position, source.length()); i++) {
            caret.append(source.charAt(i) == '\t' ? '\t' : ' ');
        }
        caret.append("^ ").append(getMessage());
        return source + "\n" + caret;
    }
}

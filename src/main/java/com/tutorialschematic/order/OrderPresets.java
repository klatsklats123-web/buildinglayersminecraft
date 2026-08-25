package com.tutorialschematic.order;

import java.util.ArrayList;
import java.util.List;

/**
 * Готовые анимации.
 *
 * <p>Пресет — это не отдельный режим, а просто набор формул, который подставляется
 * в поля сортировки. Поэтому любой из них можно взять за основу и доработать:
 * нажал «Спираль», увидел {@code min(x, X-1-x, z, Z-1-z)} в поле, поменял под себя.
 */
public final class OrderPresets {

    /**
     * @param name       подпись на кнопке
     * @param hint       что делает формула — показывается в подсказке
     * @param formulas   формулы уровней сортировки, от главного к второстепенным
     * @param descending для каждого уровня: сортировать в обратную сторону
     * @param batchSize  сколько блоков за шаг выглядит лучше всего для этой анимации
     */
    public record Preset(String name, String hint, String[] formulas, boolean[] descending, int batchSize) {

        public OrderConfig toConfig() {
            OrderConfig config = new OrderConfig();
            applyTo(config);
            return config;
        }

        /** Подставляет формулы пресета в существующую настройку, сохраняя скорость и семя. */
        public void applyTo(OrderConfig config) {
            config.keys().clear();
            for (int i = 0; i < formulas.length; i++) {
                config.keys().add(new SortKey(formulas[i], i < descending.length && descending[i]));
            }
            if (config.keys().isEmpty()) {
                config.keys().add(new SortKey("y"));
            }
            config.setBatchSize(batchSize);
        }
    }

    private static final List<Preset> PRESETS = new ArrayList<>();

    private OrderPresets() {
    }

    private static void add(String name, String hint, String[] formulas, boolean[] descending, int batch) {
        PRESETS.add(new Preset(name, hint, formulas, descending, batch));
    }

    static {
        add("Снизу вверх",
                "Самое простое: ключ — высота блока. Ряд за рядом от фундамента к крыше.",
                new String[]{"y"}, new boolean[]{false}, 1);

        add("Сверху вниз",
                "Та же высота, но в обратную сторону — галочка «наоборот» на уровне.",
                new String[]{"y"}, new boolean[]{true}, 1);

        add("Слоями по кругу",
                "Сначала высота, потом угол: каждый ряд обходится по часовой стрелке.",
                new String[]{"y", "a"}, new boolean[]{false, false}, 1);

        add("Диагональю",
                "x + z даёт одинаковый ключ на диагонали, поэтому фронт идёт углом.",
                new String[]{"x + z"}, new boolean[]{false}, 1);

        add("Змейкой",
                "Ряд за рядом туда-обратно: в чётных рядах x растёт, в нечётных убывает.",
                new String[]{"y", "z", "mod(z,2)==0 ? x : X-1-x"}, new boolean[]{false, false, false}, 1);

        add("Волной",
                "К координате добавлен синус — фронт постройки идёт волнистой линией.",
                new String[]{"x + sin(z*40)*3"}, new boolean[]{false}, 1);

        add("От центра наружу",
                "r — расстояние от центра по горизонтали. Круги расходятся от середины.",
                new String[]{"r"}, new boolean[]{false}, 2);

        add("К центру",
                "То же расстояние, но наоборот: постройка сходится к середине.",
                new String[]{"r"}, new boolean[]{true}, 2);

        add("Сферой",
                "r3 — расстояние в 3D. Растёт шар из центра слоя, красиво для куполов.",
                new String[]{"r3"}, new boolean[]{false}, 2);

        add("Стрелкой часов",
                "a — угол вокруг центра, 0..360. Блоки обходятся как стрелка по циферблату.",
                new String[]{"a"}, new boolean[]{false}, 1);

        add("Две полоски навстречу",
                "min(x, X-1-x) даёт одинаковый ключ у левого и правого края — они идут навстречу.",
                new String[]{"min(x, X-1-x)"}, new boolean[]{false}, 2);

        add("С четырёх сторон",
                "То же самое, но по обеим осям: стены сходятся со всех сторон одновременно.",
                new String[]{"min(x, X-1-x, z, Z-1-z)"}, new boolean[]{false}, 4);

        add("Спираль внутрь",
                "Сначала кольцо периметра, внутри кольца — по углу. Получается спираль.",
                new String[]{"min(x, X-1-x, z, Z-1-z)", "a"}, new boolean[]{false, false}, 1);

        add("Спираль наружу",
                "Та же спираль, но от середины к краям — первый уровень наоборот.",
                new String[]{"min(x, X-1-x, z, Z-1-z)", "a"}, new boolean[]{true, false}, 1);

        add("Случайно",
                "rand — своё стабильное число у каждого блока. Порядок один и тот же при каждом запуске.",
                new String[]{"rand"}, new boolean[]{false}, 3);

        add("Дождь сверху",
                "Высота наоборот плюс немного случайности — ряды слегка перемешиваются между собой.",
                new String[]{"-y + rand*3"}, new boolean[]{false}, 2);

        add("Проявление шумом",
                "Плавный шум вместо координат: постройка проступает рваными пятнами.",
                new String[]{"noise(x*0.3, y*0.3, z*0.3)"}, new boolean[]{false}, 3);

        add("Сначала каркас",
                "Сравнение даёт 1 или 0, поэтому им можно задавать приоритет: углы и края идут первыми.",
                new String[]{"(x>0 && x<X-1 && z>0 && z<Z-1) * 1", "y"}, new boolean[]{false, false}, 1);

        // Переменная d — расстояние по самой постройке, а не по прямой. В отличие от r
        // она обтекает пустоту, поэтому на обводке крыши или на арке фронт идёт по
        // конструкции и не перепрыгивает на другую сторону дома.
        add("Огоньком по постройке",
                "Идёт от выбранной точки по самим блокам, обтекая дырки. Точка старта — "
                        + "ПКМ по блоку в превью; без выбора начинает снизу.",
                new String[]{"d"}, new boolean[]{false}, 1);

        add("Огоньком с покачиванием",
                "То же, но фронт слегка виляет — живее, чем ровная волна.",
                new String[]{"d + sin(a*2)*2"}, new boolean[]{false}, 1);

        add("Огоньком, снизу при равенстве",
                "Идёт по постройке, а блоки на одном расстоянии ставит снизу вверх.",
                new String[]{"d", "y"}, new boolean[]{false, false}, 1);
    }

    public static List<Preset> all() {
        return java.util.Collections.unmodifiableList(PRESETS);
    }

    public static Preset byName(String name) {
        for (Preset preset : PRESETS) {
            if (preset.name().equals(name)) {
                return preset;
            }
        }
        return null;
    }

    /** Анимация по умолчанию для только что созданного слоя. */
    public static OrderConfig defaultConfig() {
        return PRESETS.get(0).toConfig();
    }
}

package com.tutorialschematic.order;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройка анимации одного слоя: чем сортируем блоки, по сколько ставим за шаг
 * и с какой скоростью.
 *
 * <p>Сортировка многоуровневая — как в таблицах: «сначала по y, потом по a, потом по r».
 * Благодаря этому не нужно вручную домножать слагаемые на большие числа, чтобы задать
 * приоритет: каждый следующий ключ разрешает только те совпадения, которые не развёл
 * предыдущий.
 */
public final class OrderConfig {

    /** Максимум уровней сортировки — больше трёх на практике не нужно, а UI остаётся читаемым. */
    public static final int MAX_KEYS = 4;

    private final List<SortKey> keys = new ArrayList<>();
    private int batchSize = 1;
    private int ticksPerStep = 2;
    /**
     * Резать ли шаги по фронту, а не по счёту. Включено — за шаг встают все блоки с
     * одинаковым значением формулы, и ширину шага задаёт сама фигура: нитка раздвоилась,
     * значит и блоков за шаг стало два. Выключено — ровно {@code batchSize} блоков.
     */
    private boolean frontStep;
    private long seed = 12345L;

    public OrderConfig() {
        keys.add(new SortKey("y"));
    }

    public static OrderConfig of(String... formulas) {
        OrderConfig config = new OrderConfig();
        config.keys.clear();
        for (String f : formulas) {
            config.keys.add(new SortKey(f));
        }
        if (config.keys.isEmpty()) {
            config.keys.add(new SortKey("y"));
        }
        return config;
    }

    public List<SortKey> keys() {
        return keys;
    }

    public SortKey key(int index) {
        return index >= 0 && index < keys.size() ? keys.get(index) : null;
    }

    public boolean canAddKey() {
        return keys.size() < MAX_KEYS;
    }

    public SortKey addKey(String formula) {
        if (!canAddKey()) {
            return null;
        }
        SortKey key = new SortKey(formula);
        keys.add(key);
        return key;
    }

    /** Удаляет уровень сортировки. Последний оставшийся не удаляется — сортировать всё равно надо. */
    public boolean removeKey(int index) {
        if (keys.size() <= 1 || index < 0 || index >= keys.size()) {
            return false;
        }
        keys.remove(index);
        return true;
    }

    public void moveKey(int from, int to) {
        if (from < 0 || from >= keys.size() || to < 0 || to >= keys.size() || from == to) {
            return;
        }
        keys.add(to, keys.remove(from));
    }

    /** Сколько блоков появляется за один шаг анимации. */
    public int batchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, Math.min(512, batchSize));
    }

    /** Пауза между шагами в игровых тиках (20 тиков = 1 секунда). */
    public boolean frontStep() {
        return frontStep;
    }

    public void setFrontStep(boolean frontStep) {
        this.frontStep = frontStep;
    }

    public int ticksPerStep() {
        return ticksPerStep;
    }

    public void setTicksPerStep(int ticks) {
        this.ticksPerStep = Math.max(0, Math.min(200, ticks));
    }

    /** Блоков в секунду — то же самое, но в понятных числах. */
    public double blocksPerSecond() {
        if (ticksPerStep <= 0) {
            return batchSize * 20.0;
        }
        return batchSize * 20.0 / ticksPerStep;
    }

    /** Семя для переменной {@code rand} и функции {@code noise}. */
    public long seed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public void rerollSeed() {
        this.seed = System.nanoTime() & 0x7FFFFFFFFFFFL;
    }

    /** Первая ошибка среди всех уровней сортировки либо {@code null}. */
    public String firstError() {
        for (int i = 0; i < keys.size(); i++) {
            SortKey key = keys.get(i);
            if (!key.isValid()) {
                return "Уровень " + (i + 1) + ": " + key.error();
            }
        }
        return null;
    }

    public boolean isValid() {
        return firstError() == null;
    }

    /** Короткое описание для списка слоёв, например «y → a ↓ | по 4». */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(keys.get(i));
        }
        if (batchSize > 1) {
            sb.append(" | по ").append(batchSize);
        }
        return sb.toString();
    }

    public OrderConfig copy() {
        OrderConfig copy = new OrderConfig();
        copy.keys.clear();
        for (SortKey key : keys) {
            copy.keys.add(key.copy());
        }
        copy.batchSize = batchSize;
        copy.ticksPerStep = ticksPerStep;
        copy.frontStep = frontStep;
        copy.seed = seed;
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        for (SortKey key : keys) {
            array.add(key.toJson());
        }
        json.add("keys", array);
        json.addProperty("batchSize", batchSize);
        json.addProperty("ticksPerStep", ticksPerStep);
        json.addProperty("frontStep", frontStep);
        json.addProperty("seed", seed);
        return json;
    }

    public static OrderConfig fromJson(JsonObject json) {
        OrderConfig config = new OrderConfig();
        config.keys.clear();
        if (json.has("keys")) {
            for (JsonElement element : json.getAsJsonArray("keys")) {
                config.keys.add(SortKey.fromJson(element.getAsJsonObject()));
            }
        }
        if (config.keys.isEmpty()) {
            config.keys.add(new SortKey("y"));
        }
        if (json.has("batchSize")) config.setBatchSize(json.get("batchSize").getAsInt());
        if (json.has("ticksPerStep")) config.setTicksPerStep(json.get("ticksPerStep").getAsInt());
        if (json.has("frontStep")) config.setFrontStep(json.get("frontStep").getAsBoolean());
        if (json.has("seed")) config.seed = json.get("seed").getAsLong();
        return config;
    }
}

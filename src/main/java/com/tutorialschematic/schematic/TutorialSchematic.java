package com.tutorialschematic.schematic;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Постройка, разложенная на слои.
 *
 * <p>Пока идёт разметка, блоки хранятся в мировых координатах — так их удобно
 * сопоставлять с тем, что игрок видит вокруг. В координаты относительно угла
 * постройки они переводятся только при сохранении в файл.
 *
 * <p>Порядок слоёв в списке и есть порядок постройки, поэтому «поднять слой выше»
 * — это просто перемещение по списку.
 */
public class TutorialSchematic {

    private String name;
    private String author = "";
    private String created = "";

    private final List<BuildLayer> layers = new ArrayList<>();
    private int nextLayerId = 1;

    public TutorialSchematic(String name) {
        this.name = name == null || name.isBlank() ? "Без названия" : name;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public String author() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author == null ? "" : author;
    }

    public String created() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created == null ? "" : created;
    }

    // ---- слои ----

    public List<BuildLayer> layers() {
        return Collections.unmodifiableList(layers);
    }

    public int layerCount() {
        return layers.size();
    }

    public BuildLayer layerAt(int index) {
        return index >= 0 && index < layers.size() ? layers.get(index) : null;
    }

    public BuildLayer layerById(int id) {
        for (BuildLayer layer : layers) {
            if (layer.id() == id) {
                return layer;
            }
        }
        return null;
    }

    public int indexOf(BuildLayer layer) {
        return layers.indexOf(layer);
    }

    public BuildLayer addLayer(String name) {
        BuildLayer layer = new BuildLayer(nextLayerId++, name == null || name.isBlank() ? "Слой " + nextLayerId : name);
        layers.add(layer);
        return layer;
    }

    /** Добавляет уже собранный слой, сохраняя его id. Используется при загрузке из файла. */
    public void addLayerRaw(BuildLayer layer) {
        layers.add(layer);
        nextLayerId = Math.max(nextLayerId, layer.id() + 1);
    }

    public boolean removeLayer(BuildLayer layer) {
        return layers.remove(layer);
    }

    /** Переставляет слой на новое место. Индексы за границами списка подрезаются. */
    public boolean moveLayer(int from, int to) {
        if (from < 0 || from >= layers.size()) {
            return false;
        }
        int target = Math.max(0, Math.min(layers.size() - 1, to));
        if (target == from) {
            return false;
        }
        layers.add(target, layers.remove(from));
        return true;
    }

    /** Двигает слой раньше в очереди постройки. */
    public boolean moveLayerUp(BuildLayer layer) {
        int index = layers.indexOf(layer);
        return index > 0 && moveLayer(index, index - 1);
    }

    /** Двигает слой позже в очереди постройки. */
    public boolean moveLayerDown(BuildLayer layer) {
        int index = layers.indexOf(layer);
        return index >= 0 && index < layers.size() - 1 && moveLayer(index, index + 1);
    }

    /**
     * Ищет слой, в котором уже лежит этот блок. Нужно, чтобы не класть один блок
     * в два слоя сразу — иначе он построится дважды.
     */
    public BuildLayer layerContaining(BlockPos pos) {
        for (BuildLayer layer : layers) {
            if (layer.contains(pos)) {
                return layer;
            }
        }
        return null;
    }

    /** Убирает блок из всех слоёв. Возвращает слой, из которого блок удалили, либо {@code null}. */
    public BuildLayer removeFromAnyLayer(BlockPos pos) {
        for (BuildLayer layer : layers) {
            if (layer.remove(pos)) {
                return layer;
            }
        }
        return null;
    }

    // ---- сводка ----

    /** Слой, в котором лежит эта декорация. Она, как и блок, может быть только в одном. */
    public BuildLayer layerContainingEntity(java.util.UUID id) {
        for (BuildLayer layer : layers) {
            if (layer.containsEntity(id)) {
                return layer;
            }
        }
        return null;
    }

    public int totalEntities() {
        int total = 0;
        for (BuildLayer layer : layers) {
            total += layer.entityCount();
        }
        return total;
    }

    public int totalBlocks() {
        int total = 0;
        for (BuildLayer layer : layers) {
            total += layer.blockCount();
        }
        return total;
    }

    /** Габариты всей постройки: {@code [minX, minY, minZ, maxX, maxY, maxZ]}, либо {@code null}. */
    public int[] bounds() {
        int[] result = null;
        for (BuildLayer layer : layers) {
            int[] b = layer.bounds();
            if (b == null) {
                continue;
            }
            if (result == null) {
                result = b.clone();
            } else {
                for (int i = 0; i < 3; i++) {
                    result[i] = Math.min(result[i], b[i]);
                    result[i + 3] = Math.max(result[i + 3], b[i + 3]);
                }
            }
        }
        return result;
    }

    /** Минимальный угол постройки — точка отсчёта при сохранении. */
    public BlockPos origin() {
        int[] b = bounds();
        return b == null ? BlockPos.ZERO : new BlockPos(b[0], b[1], b[2]);
    }

    /** Размеры постройки в блоках. */
    public int[] size() {
        int[] b = bounds();
        if (b == null) {
            return new int[]{0, 0, 0};
        }
        return new int[]{b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1};
    }

    /** Оценка общей длительности постройки в секундах при текущих настройках. */
    public double estimatedSeconds() {
        double total = 0;
        for (BuildLayer layer : layers) {
            total += layer.estimatedSeconds();
        }
        return total;
    }

    @Override
    public String toString() {
        return name + " [" + layers.size() + " слоёв, " + totalBlocks() + " бл.]";
    }
}

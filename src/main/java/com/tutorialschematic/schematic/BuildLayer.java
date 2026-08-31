package com.tutorialschematic.schematic;

import com.tutorialschematic.order.BlockOrderer;
import com.tutorialschematic.order.OrderConfig;
import com.tutorialschematic.order.OrderPresets;
import com.tutorialschematic.order.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Один этап постройки: стена, крыша, интерьер и так далее.
 *
 * <p>Слой — это произвольный набор блоков, а не горизонтальный срез: в него можно
 * положить что угодно, хоть все окна во всём доме. Порядок появления блоков внутри
 * слоя задаёт {@link #order()}.
 *
 * <p>Очередь постройки кэшируется, потому что её пересчитывают на каждом кадре превью.
 * Любое изменение набора блоков или формул сбрасывает кэш через {@link #invalidateOrder()}.
 */
public class BuildLayer {

    /** Цвета для новых слоёв — идут по кругу, чтобы соседние слои визуально различались. */
    private static final int[] PALETTE = {
            0xFF5555, 0x55FF55, 0x5555FF, 0xFFFF55, 0xFF55FF, 0x55FFFF,
            0xFFAA00, 0xAA00FF, 0x00AA88, 0xAAFF00, 0xFF0088, 0x8888FF
    };

    private final int id;
    private String name;
    private int color;

    private final Map<BlockPos, BlockData> blocks = new LinkedHashMap<>();
    /**
     * Декорации слоя: картины, рамки, стенды. Отдельно от блоков, потому что это
     * сущности — в сетке блоков их попросту нет.
     */
    private final Map<UUID, EntityData> entities = new LinkedHashMap<>();
    /**
     * Точки старта для переменной {@code d} — расстояния по постройке. Пустой список
     * означает «начать с самого нижнего блока», чтобы величина работала без настройки.
     */
    private final List<BlockPos> seeds = new ArrayList<>();
    private OrderConfig order = OrderPresets.defaultConfig();

    /**
     * Задержки до первого и после последнего блока слоя, в тиках.
     *
     * <p>Раньше пауза была одна, «после слоя», и по умолчанию равнялась двадцати тикам. На
     * записи это выходило боком: метка следующего слоя ставилась до паузы, и камера
     * переключалась за секунду до того, как в новом слое появлялся хоть один блок. Теперь
     * задержек две и они явные — паузу игрок ставит сам там, где она нужна.
     */
    private int startDelayTicks;
    private int endDelayTicks;

    /**
     * Блок-заглушка, которым слой встаёт целиком в самом своём начале; пусто — не ставить.
     *
     * <p>Пока слой не построен, на его месте пустота, и в кадре это дыра: у пола сквозь неё
     * видно, что под постройкой ничего нет. Заглушка закрывает всю площадь слоя разом, а
     * дальше настоящие блоки заменяют её по шагам — форма слоя видна с первого кадра, и
     * строится она уже поверх чего-то, а не в воздухе.
     *
     * <p>Хранится текстом в том же виде, что понимает {@code /setblock}, и разбирается
     * только при постройке: незнакомый блок не должен мешать ни открыть схему, ни
     * сохранить её.
     */
    private String placeholderBlock = "";
    /** Показывать ли слой в мире. Служебное состояние редактора, в файл не пишется. */
    private boolean visible = true;

    private transient List<Pos> cachedOrder;
    private transient List<List<Pos>> cachedSteps;

    public BuildLayer(int id, String name) {
        this.id = id;
        this.name = name;
        this.color = PALETTE[Math.floorMod(id, PALETTE.length)];
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.isBlank() ? "Слой " + id : name;
    }

    /** Цвет подсветки в формате 0xRRGGBB. */
    public int color() {
        return color;
    }

    public void setColor(int color) {
        this.color = color & 0xFFFFFF;
    }

    public OrderConfig order() {
        return order;
    }

    public void setOrder(OrderConfig order) {
        this.order = order == null ? OrderPresets.defaultConfig() : order;
        invalidateOrder();
    }

    public int startDelayTicks() {
        return startDelayTicks;
    }

    public void setStartDelayTicks(int ticks) {
        this.startDelayTicks = clampDelay(ticks);
    }

    public int endDelayTicks() {
        return endDelayTicks;
    }

    public void setEndDelayTicks(int ticks) {
        this.endDelayTicks = clampDelay(ticks);
    }

    private static int clampDelay(int ticks) {
        return Math.max(0, Math.min(20 * 60, ticks));
    }

    /** Текст заглушки как его ввели; пустая строка — заглушки нет. */
    public String placeholderBlock() {
        return placeholderBlock;
    }

    public void setPlaceholderBlock(String text) {
        this.placeholderBlock = text == null ? "" : text.trim();
    }

    /**
     * Разобранная заглушка, либо {@code null}, если её нет или запись не понята.
     *
     * <p>Не понято — значит не ставим вовсе. Подставлять что-то своё тут нельзя: заглушка
     * встаёт на всю площадь слоя, и ошибка в записи обернулась бы полом из чужого блока.
     */
    @Nullable
    public BlockState placeholderState() {
        return placeholderBlock.isEmpty() ? null : BlockData.tryParseState(placeholderBlock);
    }


    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void toggleVisible() {
        this.visible = !this.visible;
    }

    // ---- блоки ----

    public Map<BlockPos, BlockData> blocks() {
        return Collections.unmodifiableMap(blocks);
    }

    public int blockCount() {
        return blocks.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && entities.isEmpty();
    }

    public boolean contains(BlockPos pos) {
        return blocks.containsKey(pos);
    }

    public BlockData get(BlockPos pos) {
        return blocks.get(pos);
    }

    /** @return {@code true}, если блок раньше в слое не лежал */
    public boolean add(BlockPos pos, BlockData data) {
        boolean added = blocks.put(pos.immutable(), data) == null;
        if (added) {
            invalidateOrder();
        }
        return added;
    }

    /** @return {@code true}, если блок действительно был в слое */
    public boolean remove(BlockPos pos) {
        boolean removed = blocks.remove(pos) != null;
        if (removed) {
            invalidateOrder();
        }
        return removed;
    }

    public void clear() {
        blocks.clear();
        entities.clear();
        seeds.clear();
        invalidateOrder();
    }

    // ---- очередь постройки ----

    // ---- декорации ----

    public Map<UUID, EntityData> entities() {
        return Collections.unmodifiableMap(entities);
    }

    public int entityCount() {
        return entities.size();
    }

    public boolean containsEntity(UUID id) {
        return entities.containsKey(id);
    }

    public EntityData getEntity(UUID id) {
        return entities.get(id);
    }

    /** @return {@code true}, если такой декорации в слое ещё не было */
    public boolean addEntity(EntityData data) {
        boolean added = entities.put(data.id(), data) == null;
        if (added) {
            invalidateOrder();
        }
        return added;
    }

    /** @return {@code true}, если декорация действительно была в слое */
    public boolean removeEntity(UUID id) {
        boolean removed = entities.remove(id) != null;
        if (removed) {
            invalidateOrder();
        }
        return removed;
    }

    // ---- точки старта ----

    public List<BlockPos> seeds() {
        return Collections.unmodifiableList(seeds);
    }

    public boolean isSeed(BlockPos pos) {
        return seeds.contains(pos);
    }

    /** Ставит или снимает точку старта. @return {@code true}, если точка теперь стоит */
    public boolean toggleSeed(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        boolean added = !seeds.remove(immutable) && seeds.add(immutable);
        invalidateOrder();
        return added;
    }

    public void clearSeeds() {
        if (!seeds.isEmpty()) {
            seeds.clear();
            invalidateOrder();
        }
    }

    /** Для загрузки из файла — без сброса кэша, слой ещё собирается. */
    public void addSeedRaw(BlockPos pos) {
        seeds.add(pos.immutable());
    }

    private List<Pos> seedPositions() {
        List<Pos> result = new ArrayList<>(seeds.size());
        for (BlockPos pos : seeds) {
            result.add(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        }
        return result;
    }

    /** Сбрасывает кэш очереди. Вызывать после правки блоков или формул. */
    public void invalidateOrder() {
        cachedOrder = null;
        cachedSteps = null;
    }

    /**
     * Очередь постройки — блоки в том порядке, в котором они будут появляться.
     * Считается лениво и переиспользуется, пока слой не изменился.
     */
    public List<BlockPos> buildQueue() {
        List<Pos> ordered = orderedPositions();
        List<BlockPos> result = new ArrayList<>(ordered.size());
        for (Pos pos : ordered) {
            result.add(new BlockPos(pos.x(), pos.y(), pos.z()));
        }
        return result;
    }

    /** То же, но в лёгком виде — для превью, где BlockPos не нужен. */
    public List<Pos> orderedPositions() {
        List<Pos> cached = cachedOrder;
        if (cached != null) {
            return cached;
        }
        List<Pos> source = new ArrayList<>(blocks.size());
        for (BlockPos pos : blocks.keySet()) {
            source.add(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        }
        List<Pos> ordered = BlockOrderer.order(source, order, seedPositions());
        cachedOrder = ordered;
        return ordered;
    }

    /**
     * Раскадровка слоя: список шагов, в каждом — блоки, встающие одновременно. Ширина
     * шага зависит от режима: постоянная по счёту или переменная по фронту.
     */
    public List<List<Pos>> steps() {
        List<List<Pos>> cached = cachedSteps;
        if (cached != null) {
            return cached;
        }
        List<Pos> source = new ArrayList<>(blocks.size());
        for (BlockPos pos : blocks.keySet()) {
            source.add(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        }
        List<List<Pos>> computed = BlockOrderer.orderIntoSteps(source, order, seedPositions());
        cachedSteps = computed;
        return computed;
    }

    /** Та же раскадровка, но в мировых координатах — для исполнителя постройки. */
    public List<List<BlockPos>> buildSteps() {
        List<List<Pos>> source = steps();
        List<List<BlockPos>> result = new ArrayList<>(source.size());
        for (List<Pos> step : source) {
            List<BlockPos> converted = new ArrayList<>(step.size());
            for (Pos pos : step) {
                converted.add(new BlockPos(pos.x(), pos.y(), pos.z()));
            }
            result.add(converted);
        }
        return result;
    }

    /** Сколько шагов анимации займёт слой при текущих настройках. */
    public int stepCount() {
        return steps().size();
    }

    /** Примерная длительность слоя в секундах при текущих настройках скорости. */
    public double estimatedSeconds() {
        return stepCount() * Math.max(1, order.ticksPerStep()) / 20.0
                + (startDelayTicks + endDelayTicks) / 20.0;
    }

    // ---- границы ----

    /** Габариты слоя: {@code [minX, minY, minZ, maxX, maxY, maxZ]}, либо {@code null} для пустого слоя. */
    public int[] bounds() {
        if (isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        // декорации тоже расширяют коробку слоя: иначе формулы считали бы размеры
        // по одним блокам, а картина торчала бы за границей
        for (EntityData data : entities.values()) {
            BlockPos pos = data.blockPos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    @Override
    public String toString() {
        return name + " (" + blocks.size() + " бл.)";
    }
}

package com.tutorialschematic.client.selection;

import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.build.BuildRunner;
import com.tutorialschematic.schematic.BlockData;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Набор блоков в слой по клику инструментом.
 *
 * <p>Каждый режим — это просто способ превратить один клик в список позиций;
 * дальше все режимы обрабатываются одинаково, поэтому отмена, защита от
 * дублирования между слоями и сообщения написаны один раз.
 *
 * <p>ПКМ добавляет, ЛКМ убирает — как в самой игре. В режиме «Две точки» первый клик
 * любой кнопкой только ставит угол, а что случится с коробкой, решает вторая кнопка.
 */
public final class SelectionTool {

    /** Потолок для заливки: без него один клик по большой стене подвесил бы игру. */
    private static final int FLOOD_LIMIT = 16384;
    /** Потолок для области и радиусных режимов. */
    private static final int AREA_LIMIT = 65536;
    /**
     * Сколько позиций не жалко просканировать ради превью коробки. Считается каждый кадр,
     * поэтому предел на порядок ниже, чем у самого выделения: коробку крупнее показываем
     * одной рамкой, без поблочной заливки.
     */
    private static final int PREVIEW_LIMIT = 4096;

    private SelectionTool() {
    }

    /**
     * Обрабатывает клик инструментом по блоку.
     *
     * <p>В режиме «Две точки» первый клик только запоминает угол; что произойдёт с
     * коробкой, решает <b>вторая</b> кнопка. Поэтому обвести область можно любой
     * кнопкой и передумать по дороге — правая добавит, левая уберёт.
     *
     * @param target   позиция блока, по которому кликнули
     * @param removing клик левой кнопкой, то есть убираем, а не добавляем
     * @return {@code true}, если клик был обработан редактором
     */
    public static boolean handleClick(BlockPos target, boolean removing) {
        EditorState state = EditorState.get();
        Minecraft client = Minecraft.getInstance();
        Level level = client.level;

        if (level == null || !state.markupEnabled() || !state.hasSchematic()) {
            return false;
        }
        BuildLayer layer = state.activeLayer();
        if (layer == null) {
            EditorState.error("Сначала создайте слой: G → + Новый слой");
            return true;
        }

        List<BlockPos> positions;

        if (state.mode() == SelectionMode.TWO_POINTS) {
            BlockPos first = state.boxCorner();
            if (first == null) {
                state.setBoxCorner(target.immutable());
                EditorState.actionBar("Первый угол: " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + " — ПКМ по второму добавит, ЛКМ уберёт");
                return true;
            }
            positions = boxBetween(level, first, target);
            state.setBoxCorner(null);
        } else {
            positions = collect(state, level, target);
        }

        if (positions.isEmpty()) {
            EditorState.actionBar("Подходящих блоков не нашлось");
            return true;
        }

        if (removing) {
            applyRemove(state, layer, positions);
        } else {
            applyAdd(state, layer, positions, level);
        }
        return true;
    }

    private static List<BlockPos> collect(EditorState state, Level level, BlockPos target) {
        return switch (state.mode()) {
            case SINGLE, TWO_POINTS -> List.of(target);
            case FLOOD -> flood(level, target);
        };
    }

    // ---- применение ----

    private static void applyAdd(EditorState state, BuildLayer layer, List<BlockPos> positions, Level level) {
        TutorialSchematic schematic = state.schematic();
        List<BlockPos> added = new ArrayList<>();
        int movedFromOtherLayer = 0;

        for (BlockPos pos : positions) {
            BlockData data = capture(level, pos);
            if (data == null) {
                continue;
            }
            BuildLayer owner = schematic.layerContaining(pos);
            if (owner == layer) {
                continue;
            }
            if (owner != null) {
                // блок нельзя держать в двух слоях — иначе он построится дважды
                owner.remove(pos);
                movedFromOtherLayer++;
            }
            if (layer.add(pos, data)) {
                added.add(pos.immutable());
            }
        }

        // состояния блоков уже сняты выше, так что мир можно чистить без потерь
        if (state.autoClear() && !added.isEmpty()) {
            BuildRunner.get().clearFromWorld(added);
        }

        StringBuilder message = new StringBuilder("+" + added.size() + " бл. → «" + layer.name() + "» (всего "
                + layer.blockCount() + ")");
        if (movedFromOtherLayer > 0) {
            message.append(", перенесено из других слоёв: ").append(movedFromOtherLayer);
        }
        EditorState.actionBar(message.toString());
    }

    private static void applyRemove(EditorState state, BuildLayer layer, List<BlockPos> positions) {
        List<BlockPos> present = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (layer.contains(pos)) {
                present.add(pos.immutable());
            }
        }

        // При живом сносе блок возвращаем в мир до удаления из слоя: состояние хранится
        // в самом слое, и после удаления восстанавливать было бы уже нечего.
        if (state.autoClear() && !present.isEmpty()) {
            BuildRunner.get().restoreToWorld(layer, present);
        }

        int removed = 0;
        for (BlockPos pos : present) {
            if (layer.remove(pos)) {
                removed++;
            }
        }
        EditorState.actionBar("−" + removed + " бл. из «" + layer.name() + "» (осталось "
                + layer.blockCount() + ")");
    }

    /** Снимает состояние блока вместе с данными контейнера. Воздух пропускается. */
    @Nullable
    public static BlockData capture(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        CompoundTag nbt = null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            try {
                nbt = entity.saveWithoutMetadata(level.registryAccess());
            } catch (Exception ignored) {
                // на клиенте у части блоков данных просто нет — это не ошибка
            }
        }
        return new BlockData(state, nbt);
    }

    // ---- режимы ----

    /**
     * Заливка: расходится от блока по соседям с тем же блоком.
     * Сравнивается именно блок, а не полное состояние — иначе ступеньки с разным
     * поворотом считались бы разными материалами и заливка обрывалась бы на каждом углу.
     */
    private static List<BlockPos> flood(Level level, BlockPos start) {
        BlockState startState = level.getBlockState(start);
        if (startState.isAir()) {
            return List.of();
        }
        Set<BlockPos> visited = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        visited.add(start.immutable());

        while (!queue.isEmpty() && visited.size() < FLOOD_LIMIT) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (visited.contains(next)) {
                    continue;
                }
                if (level.getBlockState(next).is(startState.getBlock())) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        if (visited.size() >= FLOOD_LIMIT) {
            EditorState.error("Заливка остановлена на " + FLOOD_LIMIT + " блоках — область слишком большая");
        }
        return new ArrayList<>(visited);
    }

    /** Все непустые блоки в коробке между двумя углами включительно. */
    private static List<BlockPos> boxBetween(Level level, BlockPos first, BlockPos second) {
        return boxBetween(level, first, second, false);
    }

    /**
     * То же, но {@code quiet} глушит сообщение о слишком большой области: превью считается
     * каждый кадр, и жалоба в чат превратилась бы в поток по строке на кадр.
     */
    private static List<BlockPos> boxBetween(Level level, BlockPos first, BlockPos second, boolean quiet) {
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());

        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > AREA_LIMIT) {
            if (!quiet) {
                EditorState.error("Область слишком большая: " + volume + " позиций (предел " + AREA_LIMIT + ")");
            }
            return List.of();
        }

        List<BlockPos> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        result.add(pos);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Позиции, которые будут затронуты кликом прямо сейчас — для подсветки под прицелом.
     *
     * <p>В режиме «Две точки» после первого угла показывается вся коробка целиком: иначе
     * не видно, что именно заберёт второй клик, и в слой стабильно попадает лишнее —
     * коробка захватывает всё непустое в объёме, включая землю под постройкой.
     *
     * <p>Заливку каждый кадр считать слишком дорого, для неё подсвечивается сам блок.
     */
    public static Set<BlockPos> previewPositions(Level level, BlockPos target) {
        EditorState state = EditorState.get();
        BlockPos corner = state.boxCorner();
        if (state.mode() == SelectionMode.TWO_POINTS && corner != null
                && volume(corner, target) <= PREVIEW_LIMIT) {
            return new LinkedHashSet<>(boxBetween(level, corner, target, true));
        }
        return new HashSet<>(Set.of(target));
    }

    /** Сколько позиций в коробке между двумя углами включительно. */
    private static long volume(BlockPos first, BlockPos second) {
        return (long) (Math.abs(first.getX() - second.getX()) + 1)
                * (Math.abs(first.getY() - second.getY()) + 1)
                * (Math.abs(first.getZ() - second.getZ()) + 1);
    }
}

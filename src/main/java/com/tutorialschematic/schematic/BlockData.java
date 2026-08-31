package com.tutorialschematic.schematic;

import com.tutorialschematic.TutorialSchematicMod;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * Состояние блока вместе с данными контейнера, если они есть.
 *
 * <p>NBT нужен, чтобы сундуки, таблички и баннеры восстанавливались с содержимым,
 * а не пустыми. У обычных блоков он равен {@code null}.
 */
public record BlockData(BlockState state, @Nullable CompoundTag nbt) {

    public BlockData(BlockState state) {
        this(state, null);
    }

    public boolean hasNbt() {
        return nbt != null && !nbt.isEmpty();
    }

    /**
     * Текстовая запись состояния в том же виде, что понимает команда /setblock,
     * например {@code minecraft:oak_stairs[facing=north,half=bottom]}.
     */
    public String serializeState() {
        return BlockStateParser.serialize(state);
    }

    /** NBT в формате SNBT либо {@code null}, если данных нет. */
    @Nullable
    public String serializeNbt() {
        return hasNbt() ? NbtUtils.structureToSnbt(nbt) : null;
    }

    /**
     * Разбирает текстовое состояние. Неизвестный блок (мод удалили, версия сменилась)
     * не должен рушить загрузку всей схемы, поэтому подставляется камень и пишется в лог.
     */
    public static BlockState parseState(String text) {
        BlockState state = tryParseState(text);
        if (state == null) {
            TutorialSchematicMod.LOGGER.warn("Не удалось прочитать состояние блока '{}'", text);
            return Blocks.STONE.defaultBlockState();
        }
        return state;
    }

    /**
     * То же, но без подстановки камня: {@code null} означает «запись не понята».
     *
     * <p>Нужно там, где выбор блока делает игрок и ошибку надо показать ему, а не молча
     * подменить блок на другой.
     */
    @Nullable
    public static BlockState tryParseState(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text.trim(), false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static CompoundTag parseNbt(@Nullable String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return null;
        }
        try {
            return NbtUtils.snbtToStructure(snbt);
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.warn("Не удалось прочитать NBT блока: {}", e.getMessage());
            return null;
        }
    }
}

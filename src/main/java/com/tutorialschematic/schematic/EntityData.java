package com.tutorialschematic.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Декорация, которая живёт не в сетке блоков: картина, рамка, стенд с бронёй, дисплей.
 *
 * <p>Такие вещи — сущности, а не блоки, поэтому в {@link BuildLayer} они лежат отдельным
 * списком. Позиция у них дробная, и округлять её нельзя: рамка висит на грани блока, а
 * картина крупнее одной клетки — от округления они переехали бы на полблока.
 *
 * <p>Опознаются по {@code id} — это UUID той сущности, которую разметили. Он же ставится
 * при постройке заново, чтобы снос находил ровно то, что поставил мод, и не трогал чужое.
 */
public record EntityData(UUID id, String typeId, double x, double y, double z, CompoundTag nbt) {

    /** Клетка, в которой числится сущность — для формул и габаритов слоя. */
    public BlockPos blockPos() {
        return BlockPos.containing(x, y, z);
    }

    /** Та же запись, сдвинутая на заданное смещение. Нужно при загрузке из файла. */
    public EntityData movedBy(double dx, double dy, double dz) {
        return new EntityData(id, typeId, x + dx, y + dy, z + dz, nbt);
    }

    /** Короткое имя для сообщений: {@code minecraft:item_frame} → {@code item_frame}. */
    public String shortName() {
        int colon = typeId.indexOf(':');
        return colon < 0 ? typeId : typeId.substring(colon + 1);
    }
}

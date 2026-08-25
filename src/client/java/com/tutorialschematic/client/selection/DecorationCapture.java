package com.tutorialschematic.client.selection;

import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.schematic.EntityData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jetbrains.annotations.Nullable;

/**
 * Съём декораций — того, что стоит в мире, но блоком не является.
 *
 * <p>Берём только то, что действительно часть постройки и не двигается само: картины,
 * рамки (в том числе светящиеся), стенды с бронёй и дисплеи. Мобы, выпавшие предметы,
 * лодки и стрелы намеренно пропускаются — иначе в схему уедет случайная корова, а при
 * «постройке» она появится заново.
 *
 * <p>{@link HangingEntity} — общий предок картин и обеих рамок, поэтому проверка одна.
 */
public final class DecorationCapture {

    private DecorationCapture() {
    }

    /** Годится ли сущность в схему. */
    public static boolean isDecoration(Entity entity) {
        return entity instanceof HangingEntity
                || entity instanceof ArmorStand
                || entity instanceof Display;
    }

    /**
     * Снимает сущность целиком: тип, позицию и все данные — предмет в рамке, поворот
     * картины, броню на стенде.
     *
     * @return запись либо {@code null}, если сущность не декорация или снять не удалось
     */
    @Nullable
    public static EntityData capture(Entity entity) {
        if (!isDecoration(entity)) {
            return null;
        }
        try {
            TagValueOutput output = TagValueOutput.createWithContext(
                    ProblemReporter.DISCARDING, entity.registryAccess());
            entity.saveWithoutId(output);
            CompoundTag nbt = output.buildResult();

            // тип пишем сами: saveWithoutId его намеренно не сохраняет
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            nbt.putString("id", typeId);

            return new EntityData(entity.getUUID(), typeId,
                    entity.getX(), entity.getY(), entity.getZ(), nbt);
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.warn("Не удалось снять декорацию {}: {}",
                    entity.getType(), e.getMessage());
            return null;
        }
    }
}

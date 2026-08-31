package com.tutorialschematic.client.selection;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Блок, которым разметка заполняет пустоту.
 *
 * <p>Разметка берёт из мира то, что там уже стоит, и пустые места пропускает. Для пола это
 * неудобно: если в размеченном прямоугольнике есть дырки, в постройке они дырками и
 * останутся. Заполнение закрывает их выбранным блоком, и пол выходит сплошным, не строясь
 * заранее руками.
 *
 * <p>Блок берётся из левой руки. Правая занята инструментом разметки, поэтому отдельного
 * выбора материала не нужно: положил в левую руку доски — заполняется досками. Так же
 * работает и подсказка на экране, и подсветка под прицелом.
 */
public final class FillMaterial {

    private FillMaterial() {
    }

    /** Блок из левой руки, либо {@code null}, если там пусто или не блок. */
    @Nullable
    public static BlockState heldState(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        ItemStack stack = player.getOffhandItem();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        return item.getBlock().defaultBlockState();
    }

    @Nullable
    public static BlockState heldState() {
        return heldState(Minecraft.getInstance().player);
    }

    /** Название блока для подсказки на экране, либо {@code null}. */
    @Nullable
    public static String heldName() {
        BlockState state = heldState();
        return state == null ? null : state.getBlock().getName().getString();
    }
}

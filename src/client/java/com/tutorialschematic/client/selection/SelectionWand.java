package com.tutorialschematic.client.selection;

import com.tutorialschematic.client.EditorState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Инструмент разметки — обычный кусок блэйз-рода в руке.
 *
 * <p>Редактор перехватывает клики только пока он в главной руке. Так разметка не
 * мешает обычной игре: убрал инструмент — и кирка снова ломает блоки, а не добавляет
 * их в слой.
 */
public final class SelectionWand {

    /** Предмет-инструмент. Взят такой, которым точно не строят. */
    public static final Item ITEM = Items.BLAZE_ROD;

    private SelectionWand() {
    }

    public static ItemStack createStack() {
        ItemStack stack = new ItemStack(ITEM);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Разметка слоёв").withStyle(ChatFormatting.AQUA));
        return stack;
    }

    /** Держит ли игрок инструмент в основной руке. */
    public static boolean isHeld(Player player) {
        return player != null && player.getMainHandItem().getItem() == ITEM;
    }

    public static boolean isHeldByLocalPlayer() {
        return isHeld(Minecraft.getInstance().player);
    }

    /**
     * Кладёт инструмент в выбранный слот. Работает только в креативе — там клиент
     * может менять инвентарь сам; в выживании остаётся взять предмет обычным способом.
     */
    public static void give() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }
        if (!client.player.isCreative()) {
            EditorState.error("Выдать инструмент можно только в креативе. "
                    + "Нужен обычный blaze rod — возьмите его любым способом.");
            return;
        }
        // слоты хотбара в контейнере игрока идут с 36-го
        int slot = 36 + client.player.getInventory().getSelectedSlot();
        client.gameMode.handleCreativeModeItemAdd(createStack(), slot);
        EditorState.info("Инструмент разметки в руке. ЛКМ — добавить, ПКМ — убрать, V — сменить режим.");
    }
}

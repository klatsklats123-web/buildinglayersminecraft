package com.tutorialschematic.client.screen;

import com.tutorialschematic.order.OrderPresets;
import com.tutorialschematic.schematic.BuildLayer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Список готовых анимаций.
 *
 * <p>Пресет не «включается» — он вставляет свои формулы в поля редактора. Поэтому
 * рядом с названием сразу видно, из чего он состоит: это основной способ понять,
 * как формулы устроены, и начать собирать свои.
 */
public class PresetPickerScreen extends Screen {

    private static final int ROW_HEIGHT = 34;
    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;
    private static final int FORMULA = 0xFF7FD88F;

    private final AnimationEditorScreen parent;
    private final BuildLayer layer;
    private final List<OrderPresets.Preset> presets = OrderPresets.all();

    private int listX, listY, listWidth, listHeight;
    private int scroll;

    public PresetPickerScreen(AnimationEditorScreen parent, BuildLayer layer) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Готовые анимации"));
        this.parent = parent;
        this.layer = layer;
    }

    @Override
    protected void init() {
        listWidth = Math.min(460, width - 40);
        listX = (width - listWidth) / 2;
        listY = 40;
        listHeight = height - listY - 44;

        addRenderableWidget(Button.builder(Component.literal("Назад"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(listX, listY, listX + listWidth, listY + listHeight, PANEL_BG);
        graphics.outline(listX, listY, listWidth, listHeight, PANEL_BORDER);

        graphics.text(font, Component.literal("Готовые анимации").withStyle(ChatFormatting.BOLD),
                listX, 16, TEXT);
        graphics.text(font, "Клик подставит формулы в поля — дальше их можно править",
                listX + 150, 17, TEXT_DIM);

        graphics.enableScissor(listX + 1, listY + 1, listX + listWidth - 1, listY + listHeight - 1);
        int rowY = listY + 4 - scroll;
        for (OrderPresets.Preset preset : presets) {
            boolean hovered = mouseX >= listX && mouseX < listX + listWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) {
                graphics.fill(listX + 1, rowY, listX + listWidth - 1, rowY + ROW_HEIGHT, 0x33FFFFFF);
            }

            graphics.text(font, Component.literal(preset.name()), listX + 8, rowY + 3, TEXT);
            graphics.text(font, String.join("  →  ", preset.formulas()), listX + 8, rowY + 14, FORMULA);
            String hint = font.plainSubstrByWidth(preset.hint(), listWidth - 16);
            graphics.text(font, hint, listX + 8, rowY + 24, TEXT_DIM);

            rowY += ROW_HEIGHT;
        }
        graphics.disableScissor();

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();
        if (layer != null && mouseX >= listX && mouseX < listX + listWidth
                && mouseY >= listY && mouseY < listY + listHeight) {
            int index = (int) ((mouseY - listY - 4 + scroll) / ROW_HEIGHT);
            if (index >= 0 && index < presets.size()) {
                presets.get(index).applyTo(layer.order());
                layer.invalidateOrder();
                onClose();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, presets.size() * ROW_HEIGHT - listHeight + 8);
        scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * ROW_HEIGHT));
        return true;
    }

    @Override
    public void onClose() {
        if (parent != null) {
            parent.onSettingsChanged();
            minecraft.setScreenAndShow(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

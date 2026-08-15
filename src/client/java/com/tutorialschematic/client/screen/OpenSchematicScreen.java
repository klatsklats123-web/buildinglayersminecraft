package com.tutorialschematic.client.screen;

import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.io.SchematicFiles;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Список сохранённых схем: открыть или удалить.
 *
 * <p>Свежие сверху — почти всегда нужна именно последняя. Удаление подтверждается
 * вторым кликом по той же строке, чтобы случайный промах не стёр работу.
 */
public class OpenSchematicScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;
    private static final int DANGER = 0xFFFF6B6B;

    private final Screen parent;
    private final List<String> files = new ArrayList<>();

    private int panelX, panelY, panelWidth, panelHeight;
    private int scroll;
    private int pendingDelete = -1;

    public OpenSchematicScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Открыть схему"));
        this.parent = parent;
        this.files.addAll(SchematicFiles.list());
    }

    @Override
    protected void init() {
        panelWidth = Math.min(420, width - 40);
        panelX = (width - panelWidth) / 2;
        panelY = 40;
        panelHeight = height - panelY - 44;

        addRenderableWidget(Button.builder(Component.literal("Назад"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);

        graphics.text(font, Component.literal("Сохранённые схемы").withStyle(ChatFormatting.BOLD),
                panelX, 18, TEXT);

        if (files.isEmpty()) {
            graphics.text(font, "Пока ничего не сохранено", panelX + 10, panelY + 10, TEXT_DIM);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }

        graphics.enableScissor(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + panelHeight - 1);
        int rowY = panelY + 4 - scroll;
        for (int i = 0; i < files.size(); i++) {
            boolean hovered = mouseX >= panelX && mouseX < panelX + panelWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) {
                graphics.fill(panelX + 1, rowY, panelX + panelWidth - 1, rowY + ROW_HEIGHT, 0x33FFFFFF);
            }

            graphics.text(font, font.plainSubstrByWidth(files.get(i), panelWidth - 80),
                    panelX + 8, rowY + 6, TEXT);

            String deleteLabel = pendingDelete == i ? "точно?" : "удалить";
            graphics.text(font, deleteLabel,
                    panelX + panelWidth - 8 - font.width(deleteLabel), rowY + 6,
                    pendingDelete == i ? DANGER : TEXT_DIM);

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
        if (mouseX < panelX || mouseX >= panelX + panelWidth || mouseY < panelY || mouseY >= panelY + panelHeight) {
            return false;
        }

        int index = (int) ((mouseY - panelY - 4 + scroll) / ROW_HEIGHT);
        if (index < 0 || index >= files.size()) {
            return false;
        }

        boolean onDeleteZone = mouseX > panelX + panelWidth - 60;
        if (onDeleteZone) {
            if (pendingDelete == index) {
                SchematicFiles.delete(files.get(index));
                EditorState.info("Удалено: " + files.get(index));
                files.remove(index);
                pendingDelete = -1;
            } else {
                pendingDelete = index;
            }
            return true;
        }

        open(files.get(index));
        return true;
    }

    private void open(String fileName) {
        try {
            // Загружаем в начало координат: разметка велась в мировых координатах,
            // но для правки формул и порядка слоёв положение в мире не важно.
            TutorialSchematic schematic = SchematicFiles.load(fileName, BlockPos.ZERO);
            EditorState.get().openSchematic(schematic, fileName);
            EditorState.info("Открыто: " + schematic);
            minecraft.setScreenAndShow(new MainMenuScreen());
        } catch (Exception e) {
            EditorState.error("Не удалось открыть: " + e.getMessage());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, files.size() * ROW_HEIGHT - panelHeight + 8);
        scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * ROW_HEIGHT));
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

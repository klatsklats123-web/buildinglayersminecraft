package com.tutorialschematic.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Маленькое окно «введите название».
 *
 * <p>Нужно там, где раньше пришлось бы писать команду: создать схему, переименовать
 * слой, сохранить под другим именем. Enter подтверждает, Esc отменяет.
 */
public class TextPromptScreen extends Screen {

    private static final int PANEL_BG = 0xF014161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;

    private final Screen parent;
    private final String prompt;
    private final String hint;
    private final String initialValue;
    private final Consumer<String> onConfirm;

    private EditBox input;
    private int panelX, panelY, panelWidth, panelHeight;

    public TextPromptScreen(Screen parent, String prompt, String hint, String initialValue, Consumer<String> onConfirm) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal(prompt));
        this.parent = parent;
        this.prompt = prompt;
        this.hint = hint;
        this.initialValue = initialValue == null ? "" : initialValue;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(320, width - 40);
        panelHeight = 106;
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        input = new EditBox(font, panelX + 12, panelY + 42, panelWidth - 24, 20, Component.literal(prompt));
        input.setMaxLength(80);
        input.setValue(initialValue);
        addRenderableWidget(input);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> confirm())
                .bounds(panelX + 12, panelY + panelHeight - 30, (panelWidth - 32) / 2, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> onClose())
                .bounds(panelX + panelWidth / 2 + 4, panelY + panelHeight - 30, (panelWidth - 32) / 2, 20)
                .build());
    }

    private void confirm() {
        String value = input.getValue().trim();
        if (value.isEmpty()) {
            return;
        }
        onConfirm.accept(value);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);

        graphics.text(font, Component.literal(prompt).withStyle(ChatFormatting.BOLD), panelX + 12, panelY + 12, TEXT);
        if (hint != null && !hint.isEmpty()) {
            graphics.text(font, font.plainSubstrByWidth(hint, panelWidth - 24), panelX + 12, panelY + 26, TEXT_DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        return super.keyPressed(event);
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

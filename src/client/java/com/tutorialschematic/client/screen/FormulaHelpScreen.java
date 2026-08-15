package com.tutorialschematic.client.screen;

import com.tutorialschematic.formula.Functions;
import com.tutorialschematic.formula.Vars;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Справочник по формулам: переменные, функции, операторы и разобранные примеры.
 *
 * <p>Всё берётся из тех же реестров, что использует парсер, поэтому справка не
 * может разойтись с тем, что на самом деле работает.
 */
public class FormulaHelpScreen extends Screen {

    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;
    private static final int NAME = 0xFF7FD88F;
    private static final int HEADER = 0xFF5AC8FA;

    private static final int LINE_HEIGHT = 11;

    /** Строка справки: заголовок раздела либо пара «имя — описание». */
    private record Line(String name, String description, boolean header) {
    }

    private final Screen parent;
    private final List<Line> lines = new ArrayList<>();

    private int panelX, panelY, panelWidth, panelHeight;
    private int scroll;

    public FormulaHelpScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Справка по формулам"));
        this.parent = parent;
        buildLines();
    }

    private void buildLines() {
        lines.add(new Line("Как это работает", "", true));
        lines.add(new Line("", "Каждый блок получает число по формуле. Блоки ставятся по возрастанию этого числа.", false));
        lines.add(new Line("", "Если у двух блоков число совпало, их разводит следующий уровень сортировки.", false));
        lines.add(new Line("", "", false));

        lines.add(new Line("Переменные", "", true));
        for (Vars.Def def : Vars.all().values()) {
            lines.add(new Line(def.name(), def.help(), false));
        }
        lines.add(new Line("", "", false));

        lines.add(new Line("Операторы", "", true));
        lines.add(new Line("+ - * / %", "обычная арифметика, % — остаток от деления", false));
        lines.add(new Line("^", "степень: x^2", false));
        lines.add(new Line("< <= > >= == !=", "сравнение, даёт 1 или 0 — их можно складывать и умножать", false));
        lines.add(new Line("&& || !", "и, или, не", false));
        lines.add(new Line("условие ? да : нет", "выбор одного из двух значений", false));
        lines.add(new Line("pi, e, tau", "числовые постоянные", false));
        lines.add(new Line("", "", false));

        lines.add(new Line("Функции", "", true));
        lines.add(new Line("", "Углы везде в градусах: sin(90) равно 1. Для радианов — sinr, cosr, tanr, atan2r.", false));
        for (Functions.Def def : Functions.all().values()) {
            lines.add(new Line(def.signature(), def.help(), false));
        }
        lines.add(new Line("", "", false));

        lines.add(new Line("Разобранные примеры", "", true));
        lines.add(new Line("y", "снизу вверх — ключ равен высоте блока", false));
        lines.add(new Line("x + z", "по диагонали: на диагонали сумма одинаковая, поэтому фронт идёт углом", false));
        lines.add(new Line("min(x, X-1-x)", "две полоски навстречу: у зеркальных краёв ключ совпадает", false));
        lines.add(new Line("min(x, X-1-x, z, Z-1-z)", "то же по обеим осям — периметр внутрь", false));
        lines.add(new Line("r", "круги от центра наружу", false));
        lines.add(new Line("a", "обход по кругу, как стрелка часов", false));
        lines.add(new Line("x + sin(z*40)*3", "волнистый фронт: синус смещает границу туда-сюда", false));
        lines.add(new Line("mod(z,2)==0 ? x : X-1-x", "змейка: в нечётных рядах направление обратное", false));
        lines.add(new Line("(y > 4) * 100 + x", "приоритет внутри одной формулы: всё выше 4 ряда уходит в конец", false));
        lines.add(new Line("-y + rand*3", "сверху вниз, но ряды слегка перемешаны между собой", false));
        lines.add(new Line("noise(x*0.3, y*0.3, z*0.3)", "постройка проступает рваными пятнами", false));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(520, width - 40);
        panelX = (width - panelWidth) / 2;
        panelY = 34;
        panelHeight = height - panelY - 44;

        addRenderableWidget(Button.builder(Component.literal("Назад"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);

        graphics.text(font, Component.literal("Справка по формулам").withStyle(ChatFormatting.BOLD),
                panelX, 14, TEXT);
        graphics.text(font, "колесо мыши — прокрутка", panelX + panelWidth - 130, 15, TEXT_DIM);

        graphics.enableScissor(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + panelHeight - 1);
        int nameColumn = 140;
        int y = panelY + 5 - scroll;
        for (Line line : lines) {
            if (y > panelY - LINE_HEIGHT && y < panelY + panelHeight) {
                if (line.header()) {
                    graphics.text(font, Component.literal(line.name()).withStyle(ChatFormatting.BOLD),
                            panelX + 8, y, HEADER);
                } else if (line.name().isEmpty()) {
                    graphics.text(font, font.plainSubstrByWidth(line.description(), panelWidth - 20),
                            panelX + 8, y, TEXT_DIM);
                } else {
                    graphics.text(font, line.name(), panelX + 8, y, NAME);
                    graphics.text(font, font.plainSubstrByWidth(line.description(), panelWidth - nameColumn - 16),
                            panelX + nameColumn, y, TEXT);
                }
            }
            y += LINE_HEIGHT;
        }
        graphics.disableScissor();

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, lines.size() * LINE_HEIGHT - panelHeight + 12);
        scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * LINE_HEIGHT * 3));
        return true;
    }

    @Override
    public void onClose() {
        if (parent != null) {
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

package com.tutorialschematic.client.screen;

import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.schematic.BlockData;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Список материалов схемы — сколько чего понадобится.
 *
 * <p>Окно сделано под скриншот: панель можно двигать и растягивать, а лишние подписи —
 * убрать одной клавишей, чтобы в кадр попал только сам список. Ровно так этим и пользуются:
 * готовый список материалов кладут в описание к ролику.
 *
 * <p>Считаются блоки, а не состояния: игроку нужно знать, что купить, и «дубовые ступени»
 * это один материал, в какую бы сторону они ни смотрели. Воздух в списке не нужен — его в
 * схеме и не хранят, но защита не мешает.
 */
public class MaterialsScreen extends Screen {

    /** Как сортировать список. */
    private enum Sort {
        COUNT_DESC("по количеству ↓"),
        COUNT_ASC("по количеству ↑"),
        NAME("по названию"),
        /** Порядок, который игрок выставил перетаскиванием строк. */
        CUSTOM("свой порядок");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            // Свой порядок в круг не входит: в него попадают перетаскиванием, а выходят
            // обратно к обычной сортировке.
            Sort next = values()[(ordinal() + 1) % values().length];
            return next == CUSTOM ? COUNT_DESC : next;
        }
    }

    /** Иконка собирается один раз при сборе списка, а не каждый кадр. */
    private record Material(ItemStack icon, String name, int count) {
    }

    /** Ровно под иконку предмета: она рисуется размером шестнадцать на шестнадцать. */
    private static final int ICON = 16;

    private static final int ROW_HEIGHT = 18;
    private static final int HEADER_HEIGHT = 34;
    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;
    private static final int ACCENT = 0xFF7FD88F;
    private static final int GRIP = 0xFF4A5160;

    /** Уголок для растягивания — квадрат в правом нижнем углу панели. */
    private static final int GRIP_SIZE = 10;

    /** Просвет между колонками. */
    private static final int COLUMN_GAP = 10;

    /** Больше четырёх колонок в кадр не влезает — названия начинают резаться. */
    private static final int MAX_COLUMNS = 4;

    private final Screen parent;

    private final List<Material> materials = new ArrayList<>();
    private int totalBlocks;
    private String schematicName = "";

    private int panelX, panelY, panelWidth = 240, panelHeight = 260;
    private int scroll;
    private Sort sort = Sort.COUNT_DESC;
    private int columns = 1;

    /**
     * Порядок, выставленный перетаскиванием, по названиям материалов.
     *
     * <p>Хранится не индексами, а именами: список пересобирается при каждом открытии и при
     * изменении размера окна, и по индексам порядок бы каждый раз терялся.
     */
    private final List<String> customOrder = new ArrayList<>();

    /** Строка, которую сейчас тянут; -1 — не тянут. */
    private int draggingRow = -1;
    /** Чистый вид: только список, без подсказок и рамки — то, что уходит в скриншот. */
    private boolean clean;

    private boolean draggingPanel;
    private boolean resizingPanel;
    private int grabX, grabY;

    public MaterialsScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Материалы"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        collect();
        // Панель ставим по центру только в первый раз: после переноса игроком её положение
        // должно пережить смену сортировки и изменение размера окна.
        if (panelX == 0 && panelY == 0) {
            panelX = (width - panelWidth) / 2;
            panelY = Math.max(20, (height - panelHeight) / 2);
        }
        clampPanel();
    }

    /** Собирает материалы по всем слоям схемы. */
    private void collect() {
        materials.clear();
        totalBlocks = 0;

        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic == null) {
            return;
        }
        schematicName = schematic.name();

        Map<Block, Integer> counts = new LinkedHashMap<>();
        for (BuildLayer layer : schematic.layers()) {
            for (BlockData data : layer.blocks().values()) {
                Block block = data.state().getBlock();
                if (block == Blocks.AIR) {
                    continue;
                }
                counts.merge(block, 1, Integer::sum);
                totalBlocks++;
            }
        }
        for (Map.Entry<Block, Integer> entry : counts.entrySet()) {
            // У части блоков предмета нет вовсе — вода, огонь, настенные варианты. Стопка
            // тогда пустая, и рисуется просто ничего: строка остаётся, иконки нет.
            materials.add(new Material(new ItemStack(entry.getKey()),
                    entry.getKey().getName().getString(), entry.getValue()));
        }
        applySort();
    }

    private void applySort() {
        Comparator<Material> comparator = switch (sort) {
            case COUNT_DESC -> Comparator.comparingInt(Material::count).reversed()
                    .thenComparing(Material::name);
            case COUNT_ASC -> Comparator.comparingInt(Material::count)
                    .thenComparing(Material::name);
            case NAME -> Comparator.comparing(Material::name);
            // Материал, которого в сохранённом порядке ещё нет, уходит в конец.
            case CUSTOM -> Comparator.comparingInt((Material m) -> {
                int at = customOrder.indexOf(m.name());
                return at < 0 ? Integer.MAX_VALUE : at;
            }).thenComparing(Material::name);
        };
        materials.sort(comparator);
        scroll = 0;
    }

    private void rememberOrder() {
        customOrder.clear();
        for (Material material : materials) {
            customOrder.add(material.name());
        }
    }

    private int listTop() {
        return panelY + (clean ? 16 : HEADER_HEIGHT);
    }

    private int listBottom() {
        return panelY + panelHeight - (clean ? 4 : 16);
    }

    /** Сколько строк помещается в одну колонку. */
    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT);
    }

    private int columnWidth() {
        return (panelWidth - 16 - (columns - 1) * COLUMN_GAP) / columns;
    }

    private int maxScroll() {
        return Math.max(0, materials.size() - visibleRows() * columns);
    }

    /**
     * Номер материала под курсором, либо -1.
     *
     * <p>Колонки заполняются сверху вниз, а не слева направо: список читают колонку за
     * колонкой, как в столбце цен, а не строкой поперёк всей панели.
     */
    private int entryAt(double mouseX, double mouseY) {
        if (mouseY < listTop() || mouseY >= listBottom()) {
            return -1;
        }
        int row = (int) ((mouseY - listTop()) / ROW_HEIGHT);
        int rows = visibleRows();
        if (row >= rows) {
            return -1;
        }
        int step = columnWidth() + COLUMN_GAP;
        int column = (int) ((mouseX - (panelX + 8)) / step);
        if (column < 0 || column >= columns) {
            return -1;
        }
        int index = scroll + column * rows + row;
        return index < materials.size() ? index : -1;
    }

    private void clampPanel() {
        // Каждой колонке нужна своя минимальная ширина, иначе на четырёх колонках от
        // названий остаются две буквы. Но шире экрана панель не растёт даже ради колонок.
        int minWidth = Math.min(width - 8, 180 + (columns - 1) * (140 + COLUMN_GAP));
        panelWidth = Math.max(minWidth, Math.min(width - 8, panelWidth));
        panelHeight = Math.max(90, Math.min(height - 8, panelHeight));
        panelX = Math.max(0, Math.min(width - panelWidth, panelX));
        panelY = Math.max(0, Math.min(height - panelHeight, panelY));
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Фон намеренно не затемняем: окно нужно для скриншота, и мир за ним лучше оставить
        // как есть. Сама панель почти непрозрачная, читать список это не мешает.
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);
        if (!clean) {
            graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);
        }

        int textX = panelX + 8;
        if (clean) {
            graphics.text(font, Component.literal(schematicName + " — материалы")
                    .withStyle(ChatFormatting.BOLD), textX, panelY + 4, TEXT);
        } else {
            graphics.text(font, Component.literal("Материалы: " + schematicName)
                    .withStyle(ChatFormatting.BOLD), textX, panelY + 5, TEXT);
            graphics.text(font, "видов " + materials.size() + ",  блоков " + totalBlocks
                    + "   ·   " + sort.label + (columns > 1 ? "   ·   " + columns + " колонки" : ""),
                    textX, panelY + 16, ACCENT);
        }

        graphics.enableScissor(panelX + 1, listTop() - 1, panelX + panelWidth - 1, listBottom());
        int rows = visibleRows();
        int columnWidth = columnWidth();
        for (int i = scroll; i < materials.size() && i < scroll + rows * columns; i++) {
            int slot = i - scroll;
            int columnX = panelX + 8 + (slot / rows) * (columnWidth + COLUMN_GAP);
            int rowY = listTop() + (slot % rows) * ROW_HEIGHT;

            Material material = materials.get(i);
            String count = String.valueOf(material.count());
            int countWidth = font.width(count);

            if (i == draggingRow) {
                // Тянущуюся строку видно отдельно — как в списке слоёв.
                graphics.fill(columnX - 2, rowY - 1, columnX + columnWidth + 2, rowY + ROW_HEIGHT - 1,
                        0x66C9A24A);
            }
            graphics.item(material.icon(), columnX, rowY);
            // Текст по середине иконки, а не по её верхнему краю.
            int textY = rowY + (ICON - font.lineHeight) / 2 + 1;
            int nameX = columnX + ICON + 4;
            String name = font.plainSubstrByWidth(material.name(),
                    columnWidth - (ICON + 4) - 8 - countWidth);
            graphics.text(font, name, nameX, textY, TEXT);
            graphics.text(font, count, columnX + columnWidth - countWidth, textY, ACCENT);
        }
        graphics.disableScissor();

        if (clean) {
            return;
        }

        String hint = maxScroll() > 0
                ? "колесо — прокрутка · тянуть строку — переставить · 1–4 — колонки · S — сортировка · C — чистый вид"
                : "тянуть строку — переставить · 1–4 — колонки · угол — размер · S — сортировка · C — чистый вид";
        graphics.text(font, font.plainSubstrByWidth(hint, panelWidth - 12),
                textX, panelY + panelHeight - 12, TEXT_DIM);

        // уголок растягивания
        graphics.fill(panelX + panelWidth - GRIP_SIZE, panelY + panelHeight - GRIP_SIZE,
                panelX + panelWidth - 2, panelY + panelHeight - 2, GRIP);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();

        boolean overGrip = !clean
                && mouseX >= panelX + panelWidth - GRIP_SIZE && mouseX <= panelX + panelWidth
                && mouseY >= panelY + panelHeight - GRIP_SIZE && mouseY <= panelY + panelHeight;
        if (overGrip) {
            resizingPanel = true;
            grabX = (int) (mouseX - (panelX + panelWidth));
            grabY = (int) (mouseY - (panelY + panelHeight));
            return true;
        }
        boolean overPanel = mouseX >= panelX && mouseX < panelX + panelWidth
                && mouseY >= panelY && mouseY < panelY + panelHeight;
        if (!overPanel) {
            return false;
        }
        // Строку тянут, чтобы переставить, — как слои. Всё остальное на панели тянет саму
        // панель: заголовок, подпись внизу и пустое место под последней строкой.
        int row = entryAt(mouseX, mouseY);
        if (row >= 0) {
            draggingRow = row;
            return true;
        }
        draggingPanel = true;
        grabX = (int) (mouseX - panelX);
        grabY = (int) (mouseY - panelY);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingPanel = false;
        resizingPanel = false;
        draggingRow = -1;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingRow >= 0) {
            int target = entryAt(event.x(), event.y());
            if (target >= 0 && target != draggingRow) {
                materials.add(target, materials.remove(draggingRow));
                draggingRow = target;
                // Первое же перетаскивание отменяет автоматическую сортировку: иначе порядок
                // вернулся бы обратно при следующей пересборке списка.
                sort = Sort.CUSTOM;
                rememberOrder();
            }
            return true;
        }
        if (draggingPanel) {
            panelX = (int) (event.x() - grabX);
            panelY = (int) (event.y() - grabY);
            clampPanel();
            return true;
        }
        if (resizingPanel) {
            panelWidth = (int) (event.x() - grabX) - panelX;
            panelHeight = (int) (event.y() - grabY) - panelY;
            clampPanel();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // В несколько колонок прокрутка идёт колонками: сдвиг на одну строку перекладывал бы
        // весь список между колонками, и глазу не за что зацепиться.
        int step = columns > 1 ? visibleRows() : 1;
        scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - scrollY * step));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Код физической клавиши, а не символ: работает при любой раскладке.
        if (event.key() == GLFW.GLFW_KEY_S) {
            sort = sort.next();
            applySort();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_C) {
            clean = !clean;
            clampPanel();
            return true;
        }
        // Число колонок — цифрой: длинный список ложится в кадр в несколько столбцов,
        // а не уезжает под прокрутку.
        if (event.key() >= GLFW.GLFW_KEY_1 && event.key() < GLFW.GLFW_KEY_1 + MAX_COLUMNS) {
            columns = event.key() - GLFW.GLFW_KEY_1 + 1;
            scroll = 0;
            clampPanel();
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

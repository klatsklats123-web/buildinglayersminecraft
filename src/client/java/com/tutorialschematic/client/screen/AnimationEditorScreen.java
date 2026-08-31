package com.tutorialschematic.client.screen;

import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.build.BuildRunner;
import com.tutorialschematic.order.OrderConfig;
import com.tutorialschematic.order.SortKey;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Главный экран: слои слева, формулы порядка постройки в середине, живое превью справа.
 *
 * <p>Формулы правятся прямо в полях, и превью пересобирается на каждое изменение —
 * видно сразу, что получилось, без запуска постройки. Ошибка в формуле не ломает
 * экран: поле подсвечивается красным, под ним пишется причина, а превью продолжает
 * работать по остальным уровням.
 */
public class AnimationEditorScreen extends Screen {

    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;
    private static final int ERROR = 0xFFFF6B6B;
    private static final int WARN = 0xFFFFC46B;
    private static final int ACCENT = 0xFF5AC8FA;

    private static final int ROW_HEIGHT = 14;

    private final AnimationPreview preview = new AnimationPreview();
    private final List<EditBox> formulaBoxes = new ArrayList<>();

    private BuildLayer layer;
    /** Экран, в который возвращаемся по «Закрыть». Обычно это главное меню. */
    private final Screen parent;

    private EditBox batchBox;
    private EditBox ticksBox;
    private EditBox startDelayBox;
    private EditBox endDelayBox;

    /** Подписи строки темпа. Держим одним списком: по ним же меряется ширина колонки. */
    private static final String[] TIMING_LABELS = {
            "Блоков за шаг:", "Тиков на шаг:", "Задержка в начале:", "Задержка в конце:"
    };
    private int timingLabelWidth;
    private int timingBoxX;

    private int leftX, leftWidth;
    private int midX, midWidth;
    private int rightX, rightWidth;
    private int contentY, contentHeight;

    private int layerScroll;
    private boolean draggingPreview;
    /** Строка списка, которую сейчас тянут мышью; -1 — не тянут. */
    private int draggingLayerFrom = -1;
    private boolean layersReordered;

    public AnimationEditorScreen(BuildLayer layer) {
        this(layer, null);
    }

    public AnimationEditorScreen(BuildLayer layer, Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Анимация постройки"));
        this.layer = layer;
        this.parent = parent;
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
    protected void init() {
        formulaBoxes.clear();

        int padding = 8;
        contentY = 34;
        contentHeight = height - contentY - 34;

        leftX = padding;
        leftWidth = Math.max(110, Math.min(160, width / 6));

        rightWidth = Math.max(170, Math.min(320, width / 3));
        rightX = width - padding - rightWidth;

        midX = leftX + leftWidth + padding;
        midWidth = rightX - padding - midX;

        preview.setBounds(rightX + 4, contentY + 18, rightWidth - 8, contentHeight - 64);
        preview.setLayer(layer);

        buildFormulaRows();
        buildTimingRow();
        buildBottomBar();
        buildPreviewControls();
    }

    /** Поля формул: по одному на уровень сортировки, плюс кнопки «наоборот» и «убрать». */
    private void buildFormulaRows() {
        if (layer == null) {
            return;
        }
        OrderConfig order = layer.order();

        int rowY = contentY + 30;
        int labelWidth = 76;
        int buttonWidth = 20;
        int boxWidth = midWidth - labelWidth - buttonWidth * 2 - 12;

        for (int i = 0; i < order.keys().size(); i++) {
            SortKey key = order.key(i);
            int index = i;

            EditBox box = new EditBox(font, midX + labelWidth, rowY, boxWidth, 18,
                    Component.literal("формула"));
            box.setMaxLength(200);
            box.setValue(key.source());
            box.setHint(Component.literal("например y или min(x, X-1-x)"));
            box.setResponder(value -> {
                key.setSource(value);
                layer.invalidateOrder();
                preview.refresh();
            });
            addRenderableWidget(box);
            formulaBoxes.add(box);

            Button reverse = Button.builder(
                            Component.literal(key.descending() ? "↑" : "↓"),
                            b -> {
                                key.toggleDescending();
                                layer.invalidateOrder();
                                preview.refresh();
                                rebuildWidgets();
                            })
                    .bounds(midX + labelWidth + boxWidth + 2, rowY, buttonWidth, 18)
                    .tooltip(Tooltip.create(Component.literal(
                            "Направление сортировки. Сейчас: " + (key.descending() ? "по убыванию" : "по возрастанию"))))
                    .build();
            addRenderableWidget(reverse);

            Button remove = Button.builder(Component.literal("✕"), b -> {
                        if (order.removeKey(index)) {
                            layer.invalidateOrder();
                            preview.refresh();
                            rebuildWidgets();
                        }
                    })
                    .bounds(midX + labelWidth + boxWidth + buttonWidth + 4, rowY, buttonWidth, 18)
                    .tooltip(Tooltip.create(Component.literal(order.keys().size() > 1
                            ? "Убрать уровень сортировки"
                            : "Последний уровень убрать нельзя — сортировать будет нечем")))
                    .build();
            remove.active = order.keys().size() > 1;
            addRenderableWidget(remove);

            rowY += 34;
        }

        if (order.canAddKey()) {
            addRenderableWidget(Button.builder(Component.literal("+ уровень сортировки"), b -> {
                        order.addKey("r");
                        layer.invalidateOrder();
                        preview.refresh();
                        rebuildWidgets();
                    })
                    .bounds(midX + 76, rowY, 150, 18)
                    .tooltip(Tooltip.create(Component.literal(
                            "Следующий уровень разводит блоки, у которых совпал ключ предыдущего")))
                    .build());
        }
    }

    /** Строка настроек темпа: размер пачки, тик на шаг, задержки в начале и в конце слоя. */
    private void buildTimingRow() {
        if (layer == null) {
            return;
        }
        OrderConfig order = layer.order();
        // Полей стало четыре вместо трёх — строка начинается выше, иначе нижнее уходит
        // под кнопки.
        int rowY = contentY + contentHeight - 102;
        int boxWidth = 42;

        // Колонку под подписи меряем шрифтом, а не подбираем на глаз: подписи тут разной
        // длины, и от постоянного отступа самая длинная налезала на поле ввода.
        timingLabelWidth = 0;
        for (String label : TIMING_LABELS) {
            timingLabelWidth = Math.max(timingLabelWidth, font.width(label));
        }
        timingBoxX = midX + 6 + timingLabelWidth + 8;

        batchBox = numberBox(timingBoxX, rowY, boxWidth, order.batchSize(), value -> {
            order.setBatchSize(value);
            layer.invalidateOrder();
            preview.refresh();
        });
        ticksBox = numberBox(timingBoxX, rowY + 24, boxWidth, order.ticksPerStep(), value -> {
            order.setTicksPerStep(value);
            preview.refresh();
        });
        // Две задержки вместо прежней одной паузы: перед первым блоком слоя и после
        // последнего. Раздельно они нужны, чтобы дать камере встать до начала кладки, а не
        // только отдышаться после неё.
        startDelayBox = numberBox(timingBoxX, rowY + 48, boxWidth,
                layer.startDelayTicks(), layer::setStartDelayTicks);
        endDelayBox = numberBox(timingBoxX, rowY + 72, boxWidth,
                layer.endDelayTicks(), layer::setEndDelayTicks);

        // Кнопка рядом с размером пачки, потому что она его и отменяет: включённая
        // резка по фронту делает число блоков за шаг переменным.
        Button frontStep = Button.builder(
                        Component.literal(order.frontStep() ? "Шаг: по фронту" : "Шаг: по счёту"),
                        b -> {
                            order.setFrontStep(!order.frontStep());
                            layer.invalidateOrder();
                            preview.refresh();
                            rebuildWidgets();
                        })
                .bounds(midX + 140, rowY, 90, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "По счёту — ровно столько блоков за шаг, сколько слева.\n\n"
                                + "По фронту — за шаг встают все блоки с одинаковым значением "
                                + "формулы, и ширину задаёт сама фигура: нитка раздвоилась — "
                                + "пошло по два блока, ветки сошлись — снова по одному.\n\n"
                                + "Для d и y это то, что нужно. У дробных формул совпадений "
                                + "почти не бывает, и шаг выходит в один блок.")))
                .build();
        addRenderableWidget(frontStep);

        if (batchBox != null) {
            // при резке по фронту размер пачки ни на что не влияет — гасим, чтобы
            // не подкручивали число, от которого ничего не зависит
            batchBox.setEditable(!order.frontStep());
        }

        addRenderableWidget(Button.builder(Component.literal("Другое семя"), b -> {
                    order.rerollSeed();
                    layer.invalidateOrder();
                    preview.refresh();
                })
                .bounds(midX + 140, rowY + 48, 90, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Меняет расклад для rand и noise — другой случайный порядок при тех же формулах")))
                .build());
    }

    private EditBox numberBox(int x, int y, int width, int initial, java.util.function.IntConsumer onChange) {
        EditBox box = new EditBox(font, x, y, width, 18, Component.literal("число"));
        box.setMaxLength(4);
        box.setValue(Integer.toString(initial));
        box.setResponder(value -> {
            try {
                if (!value.isBlank()) {
                    onChange.accept(Integer.parseInt(value.trim()));
                }
            } catch (NumberFormatException ignored) {
                // пока пользователь стирает и набирает заново, строка бывает не числом
            }
        });
        addRenderableWidget(box);
        return box;
    }

    private void buildBottomBar() {
        int barY = height - 26;

        addRenderableWidget(Button.builder(Component.literal("Пресеты"),
                        b -> minecraft.setScreenAndShow(new PresetPickerScreen(this, layer)))
                .bounds(leftX, barY, 80, 20)
                .tooltip(Tooltip.create(Component.literal("Готовые анимации — вставляют формулы в поля")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Справка по формулам"),
                        b -> minecraft.setScreenAndShow(new FormulaHelpScreen(this)))
                .bounds(leftX + 84, barY, 140, 20)
                .tooltip(Tooltip.create(Component.literal("Все переменные и функции с описанием")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Сохранить схему"), b -> EditorState.get().save())
                .bounds(width - 8 - 200, barY, 96, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Закрыть"), b -> onClose())
                .bounds(width - 8 - 100, barY, 100, 20)
                .build());
    }

    private void buildPreviewControls() {
        int controlsY = contentY + contentHeight - 40;
        int buttonWidth = (rightWidth - 16) / 3;

        addRenderableWidget(Button.builder(Component.literal("▶ / ⏸"), b -> preview.togglePlaying())
                .bounds(rightX + 4, controlsY, buttonWidth, 18)
                .tooltip(Tooltip.create(Component.literal("Пауза превью")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("↺"), b -> preview.resetAnimation())
                .bounds(rightX + 8 + buttonWidth, controlsY, buttonWidth, 18)
                .tooltip(Tooltip.create(Component.literal("Проиграть заново")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Ракурс"), b -> preview.setAngles(35f, 30f))
                .bounds(rightX + 12 + buttonWidth * 2, controlsY, buttonWidth, 18)
                .tooltip(Tooltip.create(Component.literal("Сбросить поворот. Крутить — перетаскиванием, приближать — колесом")))
                .build());
    }

    // ---- отрисовка ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawPanels(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawLabels(graphics, mouseX, mouseY);
    }

    private void drawPanels(GuiGraphicsExtractor graphics) {
        panel(graphics, leftX, contentY, leftWidth, contentHeight);
        panel(graphics, midX, contentY, midWidth, contentHeight);
        panel(graphics, rightX, contentY, rightWidth, contentHeight);
    }

    private void panel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        graphics.outline(x, y, w, h, PANEL_BORDER);
    }

    private void drawLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        TutorialSchematic schematic = EditorState.get().schematic();

        graphics.text(font, Component.literal("Анимация постройки").withStyle(ChatFormatting.BOLD), 10, 12, TEXT);
        if (schematic != null) {
            graphics.text(font, Component.literal("схема «" + schematic.name() + "»"),
                    10 + font.width("Анимация постройки") + 10, 12, TEXT_DIM);
        }

        drawLayerList(graphics, mouseX, mouseY);
        drawFormulaSection(graphics);
        drawTimingSection(graphics);
        drawPreviewSection(graphics);
    }

    private void drawLayerList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, Component.literal("Слои"), leftX + 6, contentY + 6, ACCENT);
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic == null) {
            return;
        }

        // Внизу списка — строка с общей длительностью, и список до неё не доходит.
        int listTop = contentY + 20;
        int listBottom = contentY + contentHeight - 18;
        graphics.enableScissor(leftX + 1, listTop, leftX + leftWidth - 1, listBottom);

        int rowY = listTop - layerScroll;
        for (int i = 0; i < schematic.layerCount(); i++) {
            BuildLayer current = schematic.layerAt(i);
            boolean selected = current == layer;
            boolean hovered = mouseX >= leftX && mouseX < leftX + leftWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            if (i == draggingLayerFrom) {
                // тянущуюся строку видно отдельно: иначе при быстром движении непонятно,
                // какой слой ты сейчас переставляешь
                graphics.fill(leftX + 1, rowY, leftX + leftWidth - 1, rowY + ROW_HEIGHT, 0x66C9A24A);
            } else if (selected) {
                graphics.fill(leftX + 1, rowY, leftX + leftWidth - 1, rowY + ROW_HEIGHT, 0x552F6FA8);
            } else if (hovered) {
                graphics.fill(leftX + 1, rowY, leftX + leftWidth - 1, rowY + ROW_HEIGHT, 0x33FFFFFF);
            }
            // цветная метка слоя — те же цвета, что и подсветка в мире
            graphics.fill(leftX + 3, rowY + 3, leftX + 8, rowY + ROW_HEIGHT - 3, 0xFF000000 | current.color());

            // Справа — не число блоков, а сколько слой будет строиться: длительность и есть
            // то, чем меряют ролик, а блоки выбранного слоя видно под превью.
            String time = duration(buildSeconds(current));
            String label = (i + 1) + ". " + current.name();
            String trimmed = font.plainSubstrByWidth(label, leftWidth - 24 - font.width(time));
            graphics.text(font, trimmed, leftX + 12, rowY + 3, selected ? TEXT : TEXT_DIM);
            graphics.text(font, time, leftX + leftWidth - 6 - font.width(time), rowY + 3, TEXT_DIM);

            rowY += ROW_HEIGHT;
        }
        graphics.disableScissor();

        double total = 0;
        for (int i = 0; i < schematic.layerCount(); i++) {
            total += buildSeconds(schematic.layerAt(i));
        }
        graphics.fill(leftX + 1, listBottom, leftX + leftWidth - 1, listBottom + 1, PANEL_BORDER);
        double speed = BuildRunner.get().speedMultiplier();
        String totalLabel = duration(total);
        String caption = speed == 1.0 ? "Всего" : String.format("Всего · x%.1f", speed);
        graphics.text(font, caption, leftX + 6, listBottom + 5, TEXT_DIM);
        graphics.text(font, totalLabel, leftX + leftWidth - 6 - font.width(totalLabel), listBottom + 5, TEXT);
    }

    /**
     * Сколько слой будет строиться на самом деле.
     *
     * <p>Настройки слоя задают темп, а общий множитель скорости растягивает или сжимает всё
     * разом — вместе с задержками. Без него счёт врёт во столько же раз, во сколько
     * выкручена скорость: на записи, снятой втрое медленнее, слой на восемь секунд шёл
     * двадцать пять. Превью считает так же.
     */
    private static double buildSeconds(BuildLayer layer) {
        return layer.estimatedSeconds() / Math.max(0.1, BuildRunner.get().speedMultiplier());
    }

    /** Длительность человеческими единицами: секунды, пока их немного, дальше минуты. */
    private static String duration(double totalSeconds) {
        if (totalSeconds < 60) {
            return String.format("%.1f с", totalSeconds);
        }
        int whole = (int) Math.round(totalSeconds);
        return String.format("%d:%02d", whole / 60, whole % 60);
    }

    private void drawFormulaSection(GuiGraphicsExtractor graphics) {
        graphics.text(font, Component.literal("Порядок постройки"), midX + 6, contentY + 6, ACCENT);
        if (layer == null) {
            graphics.text(font, Component.literal("Слой не выбран"), midX + 6, contentY + 30, TEXT_DIM);
            return;
        }

        OrderConfig order = layer.order();
        int rowY = contentY + 30;
        for (int i = 0; i < order.keys().size(); i++) {
            SortKey key = order.key(i);
            String label = i == 0 ? "Сначала по:" : "потом по:";
            graphics.text(font, label, midX + 6, rowY + 5, TEXT);

            if (!key.isValid()) {
                graphics.text(font, "✕ " + key.error(), midX + 76, rowY + 20, ERROR);
            } else if (key.isConstant()) {
                graphics.text(font, "не зависит от блока — этот уровень ничего не сортирует",
                        midX + 76, rowY + 20, WARN);
            }
            rowY += 34;
        }
    }

    private void drawTimingSection(GuiGraphicsExtractor graphics) {
        if (layer == null) {
            return;
        }
        OrderConfig order = layer.order();
        int rowY = contentY + contentHeight - 102;

        for (int i = 0; i < TIMING_LABELS.length; i++) {
            graphics.text(font, TIMING_LABELS[i], midX + 6, rowY + 5 + i * 24, TEXT);
        }

        // Справа от полей — то же число человеческими единицами. Тики понятны не сразу,
        // а задержку игрок задумывает секундами.
        int hintX = timingBoxX + 48;
        graphics.text(font, String.format("%.1f с всего", buildSeconds(layer)), hintX, rowY + 5, TEXT_DIM);
        graphics.text(font, String.format("%.1f бл/с", order.blocksPerSecond()), hintX, rowY + 29, TEXT_DIM);
        graphics.text(font, seconds(layer.startDelayTicks()), hintX, rowY + 53, TEXT_DIM);
        graphics.text(font, seconds(layer.endDelayTicks()), hintX, rowY + 77, TEXT_DIM);
    }

    /** Тики в секундах: в игре двадцать тиков в секунде. */
    private static String seconds(int ticks) {
        return String.format("= %.1f с", ticks / 20.0);
    }

    private void drawPreviewSection(GuiGraphicsExtractor graphics) {
        graphics.text(font, Component.literal("Превью"), rightX + 6, contentY + 6, ACCENT);
        preview.render(graphics);

        if (layer == null) {
            return;
        }
        int barY = contentY + contentHeight - 58;
        int barWidth = rightWidth - 8;
        graphics.fill(rightX + 4, barY, rightX + 4 + barWidth, barY + 4, 0xFF2A2E38);
        graphics.fill(rightX + 4, barY, rightX + 4 + (int) (barWidth * preview.progress()), barY + 4, 0xFF5AC8FA);

        String info = preview.totalSteps() + " шагов · " + layer.blockCount() + " бл.";
        graphics.text(font, info, rightX + 4, barY + 8, TEXT_DIM);
    }

    // ---- ввод ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();

        if (preview.isMouseOver(mouseX, mouseY)) {
            // Правая ставит и снимает точку старта, левая только крутит. Разделение
            // жёсткое и без порогов: крутить приходится постоянно, а точка старта задаёт
            // всю анимацию — она не должна сдвигаться от случайного движения мышью.
            // Кнопки те же, что и в мире: ПКМ назначает, и здесь тоже.
            if (event.button() == 1) {
                if (preview.toggleSeedAt(mouseX, mouseY)) {
                    // порядок зависит от точек старта — очередь пересчитывается
                    preview.setLayer(layer);
                }
            } else {
                draggingPreview = true;
            }
            return true;
        }

        // выбор слоя в списке; он же начало перетаскивания — порядок слоёв это порядок
        // постройки, и менять его удобнее мышью, чем кнопками «выше/ниже»
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic != null && mouseX >= leftX && mouseX < leftX + leftWidth) {
            int index = layerRowAt(mouseY);
            if (index >= 0 && index < schematic.layerCount()) {
                selectLayer(schematic.layerAt(index));
                draggingLayerFrom = index;
                return true;
            }
        }
        return false;
    }

    /** Номер строки списка слоёв под курсором, либо -1. */
    private int layerRowAt(double mouseY) {
        int listTop = contentY + 20;
        if (mouseY < listTop) {
            return -1;
        }
        return (int) ((mouseY - listTop + layerScroll) / ROW_HEIGHT);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingPreview = false;
        draggingLayerFrom = -1;
        if (layersReordered) {
            // Виджеты пересобираем один раз, когда перетаскивание закончилось: делать это на
            // каждый шаг мыши значит пересоздавать кнопки прямо во время перетаскивания.
            layersReordered = false;
            rebuildWidgets();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingPreview) {
            preview.rotate(dragX * 0.6, -dragY * 0.6);
            return true;
        }
        if (draggingLayerFrom >= 0) {
            TutorialSchematic schematic = EditorState.get().schematic();
            if (schematic == null) {
                return true;
            }
            // Тянем строку за курсором по одному шагу: слой переставляется сразу, поэтому
            // видно результат, а не только рамку на месте будущей вставки.
            int target = Math.max(0, Math.min(schematic.layerCount() - 1, layerRowAt(event.y())));
            if (target != draggingLayerFrom && schematic.moveLayer(draggingLayerFrom, target)) {
                draggingLayerFrom = target;
                layersReordered = true;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (preview.isMouseOver(mouseX, mouseY)) {
            preview.zoom(scrollY);
            return true;
        }
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic != null && mouseX >= leftX && mouseX < leftX + leftWidth) {
            int visibleRows = (contentHeight - 38) / ROW_HEIGHT;
            int maxScroll = Math.max(0, schematic.layerCount() - visibleRows) * ROW_HEIGHT;
            layerScroll = (int) Math.max(0, Math.min(maxScroll, layerScroll - scrollY * ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // пока курсор в поле формулы, стрелки должны двигать текст, а не слои
        if (event.key() == GLFW.GLFW_KEY_PAGE_UP || event.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
            TutorialSchematic schematic = EditorState.get().schematic();
            if (schematic != null && layer != null) {
                boolean moved = event.key() == GLFW.GLFW_KEY_PAGE_UP
                        ? schematic.moveLayerUp(layer)
                        : schematic.moveLayerDown(layer);
                if (moved) {
                    rebuildWidgets();
                }
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void selectLayer(BuildLayer selected) {
        if (selected == null || selected == layer) {
            return;
        }
        this.layer = selected;
        EditorState.get().setActiveLayer(selected);
        rebuildWidgets();
    }

    /** Вызывается дочерними экранами после того, как они поменяли настройки слоя. */
    public void onSettingsChanged() {
        if (layer != null) {
            layer.invalidateOrder();
        }
        rebuildWidgets();
    }

    public BuildLayer layer() {
        return layer;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

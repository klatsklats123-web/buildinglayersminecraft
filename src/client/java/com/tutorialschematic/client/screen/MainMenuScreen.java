package com.tutorialschematic.client.screen;

import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.build.BuildRunner;
import com.tutorialschematic.client.flashback.CameraExport;
import com.tutorialschematic.client.flashback.FlashbackBridge;
import com.tutorialschematic.client.flashback.RecordedBuild;
import com.tutorialschematic.client.selection.SelectionWand;
import com.tutorialschematic.io.SchematicFiles;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;


/**
 * Главное меню мода — всё управление в одном месте, без команд.
 *
 * <p>Слева список слоёв: выбор, порядок, видимость, удаление. Справа действия,
 * сгруппированные по этапам работы — схема, разметка, анимация, постройка.
 * Подписи на кнопках показывают текущее состояние, поэтому после каждого действия
 * экран пересобирается.
 */
public class MainMenuScreen extends Screen {

    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;
    private static final int ACCENT = 0xFF5AC8FA;
    private static final int OK = 0xFF7FD88F;
    private static final int DANGER = 0xFFFF6B6B;
    /** Цвет названия скрытого слоя — заметно тусклее обычного. */
    private static final int HIDDEN = 0xFF5A5F6B;

    private static final int ROW_HEIGHT = 16;
    /** Ширина одной кнопки-иконки в строке слоя. */
    private static final int ICON = 14;
    /** Сколько иконок справа в строке: вверх, вниз, видимость, удалить. */
    private static final int ICON_COUNT = 4;

    private int leftX, leftWidth, rightX, rightWidth;
    private int contentY, contentHeight;
    private int listTop, listBottom;

    private int scroll;
    private int pendingDeleteLayer = -1;
    /** Первое нажатие «Очистить слой» только предупреждает, второе — очищает. */
    private boolean pendingClearLayer;

    public MainMenuScreen() {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Tutorial Schematic"));
    }

    @Override
    protected void init() {
        int padding = 8;
        contentY = 34;
        contentHeight = height - contentY - 34;

        leftX = padding;
        leftWidth = Math.max(200, Math.min(340, (width - padding * 3) / 2));
        rightX = leftX + leftWidth + padding;
        rightWidth = width - padding - rightX;

        listTop = contentY + 20;
        listBottom = contentY + contentHeight - 26;

        buildLayerButtons();
        buildActionButtons();
    }

    private void buildLayerButtons() {
        boolean hasSchematic = EditorState.get().hasSchematic();

        Button addLayer = Button.builder(Component.literal("+ Новый слой"), b -> promptNewLayer())
                .bounds(leftX + 4, listBottom + 4, leftWidth - 8, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Создаёт слой и делает его активным — дальше кликайте ЛКМ по блокам")))
                .build();
        addLayer.active = hasSchematic;
        addRenderableWidget(addLayer);
    }

    private void buildActionButtons() {
        EditorState state = EditorState.get();
        BuildRunner runner = BuildRunner.get();
        boolean has = state.hasSchematic();
        BuildLayer active = state.activeLayer();

        int x = rightX + 6;
        int fullWidth = rightWidth - 12;
        int halfWidth = (fullWidth - 4) / 2;
        int thirdWidth = (fullWidth - 8) / 3;
        int y = contentY + 20;

        // --- Схема ---
        addRenderableWidget(Button.builder(Component.literal("Новая"), b -> promptNewSchematic())
                .bounds(x, y, thirdWidth, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Открыть"),
                        b -> minecraft.setScreenAndShow(new OpenSchematicScreen(this)))
                .bounds(x + thirdWidth + 4, y, thirdWidth, 18).build());
        Button save = Button.builder(Component.literal("Сохранить"), b -> {
                    state.save();
                    rebuildWidgets();
                })
                .bounds(x + (thirdWidth + 4) * 2, y, thirdWidth, 18).build();
        save.active = has;
        addRenderableWidget(save);

        y += 22;
        Button saveAs = Button.builder(Component.literal("Сохранить как…"), b -> promptSaveAs())
                .bounds(x, y, halfWidth, 18).build();
        saveAs.active = has;
        addRenderableWidget(saveAs);

        Button close = Button.builder(Component.literal("Закрыть схему"), b -> {
                    state.close();
                    rebuildWidgets();
                })
                .bounds(x + halfWidth + 4, y, halfWidth, 18).build();
        close.active = has;
        addRenderableWidget(close);

        // --- Разметка ---
        y += 34;
        Button markup = Button.builder(
                        Component.literal(state.markupEnabled() ? "Разметка: ВКЛ" : "Разметка: выкл"),
                        b -> {
                            state.setMarkupEnabled(!state.markupEnabled());
                            rebuildWidgets();
                        })
                .bounds(x, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Пока включена, ЛКМ по блоку добавляет его в активный слой, а Shift+ЛКМ убирает")))
                .build();
        markup.active = has;
        addRenderableWidget(markup);

        Button wand = Button.builder(Component.literal("Выдать инструмент"), b -> {
                    SelectionWand.give();
                    onClose();
                })
                .bounds(x + halfWidth + 4, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Кладёт blaze rod в руку. Разметка работает только с ним — "
                                + "без инструмента кирка ломает блоки как обычно.")))
                .build();
        addRenderableWidget(wand);

        y += 22;
        Button mode = Button.builder(
                        Component.literal("Режим: " + state.mode().displayName()),
                        b -> {
                            state.cycleMode();
                            rebuildWidgets();
                        })
                .bounds(x, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        state.mode().hint() + "\n" + state.mode().controls())))
                .build();
        mode.active = has;
        addRenderableWidget(mode);

        // очистка слоя рядом со сменой режима: это тоже «убрать лишнее», только целиком
        Button clearLayer = Button.builder(
                        Component.literal(pendingClearLayer ? "Точно очистить?" : "Очистить слой"),
                        b -> {
                            if (!pendingClearLayer) {
                                pendingClearLayer = true;
                            } else {
                                BuildLayer target = state.activeLayer();
                                if (target != null) {
                                    int count = target.blockCount();
                                    target.clear();
                                    EditorState.info("Из «" + target.name() + "» убрано " + count + " бл.");
                                }
                                pendingClearLayer = false;
                            }
                            rebuildWidgets();
                        })
                .bounds(x + halfWidth + 4, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Убирает из активного слоя все блоки. Отменить это нельзя.")))
                .build();
        clearLayer.active = active != null && !active.isEmpty();
        addRenderableWidget(clearLayer);

        y += 22;
        Button visibility = Button.builder(
                        Component.literal(state.showOnlyActiveLayer() ? "Показ: только активный" : "Показ: все слои"),
                        b -> {
                            state.setShowOnlyActiveLayer(!state.showOnlyActiveLayer());
                            rebuildWidgets();
                        })
                .bounds(x, y, halfWidth, 18).build();
        visibility.active = has;
        addRenderableWidget(visibility);

        Button highlights = Button.builder(
                        Component.literal(state.highlightsVisible() ? "Подсветка: вкл" : "Подсветка: выкл"),
                        b -> {
                            state.setHighlightsVisible(!state.highlightsVisible());
                            rebuildWidgets();
                        })
                .bounds(x + halfWidth + 4, y, halfWidth, 18).build();
        addRenderableWidget(highlights);

        y += 22;
        Button autoClear = Button.builder(
                        Component.literal(state.autoClear()
                                ? "Живой снос: ВКЛ — размеченное исчезает"
                                : "Живой снос: выкл"),
                        b -> {
                            BuildRunner.get().setAutoClear(!state.autoClear());
                            rebuildWidgets();
                        })
                .bounds(x, y, fullWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Размеченный блок сразу убирается из мира, так что под ним видно "
                                + "следующий этап. ЛКМ возвращает блок обратно.\n\n"
                                + "При включении сносится всё уже размеченное, при выключении "
                                + "возвращается обратно.\n\n"
                                + "Подсветка остаётся — по ней и видно, где слой был. "
                                + "Гасится отдельно, клавишей H.\n\n"
                                + "Работает только в одиночном мире.")))
                .build();
        autoClear.active = has;
        addRenderableWidget(autoClear);

        // --- Анимация ---
        y += 34;
        Button anim = Button.builder(
                        Component.literal(active == null
                                ? "Настроить анимацию"
                                : "Настроить анимацию: " + shorten(active.name(), fullWidth - 130)),
                        b -> minecraft.setScreenAndShow(new AnimationEditorScreen(state.activeLayer(), this)))
                .bounds(x, y, fullWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Формулы порядка постройки и живое превью для выбранного слоя")))
                .build();
        anim.active = active != null;
        addRenderableWidget(anim);

        // --- Постройка ---
        y += 34;
        Button clear = Button.builder(Component.literal("Снести постройку"), b -> {
                    runner.clearAll();
                    rebuildWidgets();
                })
                .bounds(x, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Убирает все размеченные блоки из мира — подготовка к съёмке")))
                .build();
        clear.active = has;
        addRenderableWidget(clear);

        Button start = Button.builder(Component.literal("▶ Запустить"), b -> {
                    runner.start();
                    onClose();
                })
                .bounds(x + halfWidth + 4, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal("Возводит постройку по слоям и закрывает меню")))
                .build();
        start.active = has;
        addRenderableWidget(start);

        y += 22;
        boolean flashback = FlashbackBridge.isAvailable();

        Button record = Button.builder(Component.literal("● Запустить с записью"), b -> {
                    if (RecordedBuild.startRecordingThenBuild()) {
                        onClose();
                    }
                })
                .bounds(x, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(flashback
                        ? "Включает запись Flashback, дожидается слепка мира и только потом "
                                + "начинает строить — иначе начало постройки в реплей не попадёт.\n\n"
                                + "По ходу расставляет метки по слоям."
                        : "Flashback не установлен")))
                .build();
        record.active = has && flashback;
        addRenderableWidget(record);

        Button cameras = Button.builder(Component.literal("Расставить камеры"), b -> {
                    CameraExport.exportForNewestReplay();
                    rebuildWidgets();
                })
                .bounds(x + halfWidth + 4, y, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(flashback
                        ? "Читает свежий реплей и раскладывает по нему дорожки камер: "
                                + "дальний, средний, ближний и два пролёта.\n\n"
                                + "Запись должна быть уже остановлена — пока она идёт, реплей "
                                + "не дописан."
                        : "Flashback не установлен")))
                .build();
        cameras.active = has && flashback;
        addRenderableWidget(cameras);

        y += 22;
        Button pause = Button.builder(
                        Component.literal(runner.state() == BuildRunner.State.PAUSED ? "▶ Продолжить" : "⏸ Пауза"),
                        b -> {
                            runner.togglePause();
                            rebuildWidgets();
                        })
                .bounds(x, y, thirdWidth, 18).build();
        pause.active = runner.isActive();
        addRenderableWidget(pause);

        Button stop = Button.builder(Component.literal("Стоп"), b -> {
                    runner.stop();
                    rebuildWidgets();
                })
                .bounds(x + thirdWidth + 4, y, thirdWidth, 18).build();
        stop.active = runner.isActive();
        addRenderableWidget(stop);

        Button finish = Button.builder(Component.literal("Достроить"), b -> {
                    runner.finishInstantly();
                    rebuildWidgets();
                })
                .bounds(x + (thirdWidth + 4) * 2, y, thirdWidth, 18)
                .tooltip(Tooltip.create(Component.literal("Мгновенно ставит всё оставшееся")))
                .build();
        finish.active = has;
        addRenderableWidget(finish);

        y += 22;
        Button rewind = Button.builder(Component.literal("↺ Откатить к выбранному слою"), b -> {
                    TutorialSchematic schematic = state.schematic();
                    if (schematic != null && state.activeLayer() != null) {
                        runner.rewindTo(schematic.indexOf(state.activeLayer()));
                        rebuildWidgets();
                    }
                })
                .bounds(x, y, fullWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Слои до выбранного ставятся сразу, выбранный и следующие сносятся. "
                                + "Для пересъёмки под другим ракурсом.")))
                .build();
        rewind.active = active != null;
        addRenderableWidget(rewind);

        y += 22;
        int speedStep = 22;
        Button slower = Button.builder(Component.literal("−"), b -> {
                    runner.setSpeedMultiplier(runner.speedMultiplier() / 1.5);
                    rebuildWidgets();
                })
                .bounds(x, y, speedStep, 18).build();
        addRenderableWidget(slower);

        Button faster = Button.builder(Component.literal("+"), b -> {
                    runner.setSpeedMultiplier(runner.speedMultiplier() * 1.5);
                    rebuildWidgets();
                })
                .bounds(x + speedStep + 4, y, speedStep, 18).build();
        addRenderableWidget(faster);

        Button sounds = Button.builder(
                        Component.literal(runner.playSounds() ? "Звук: вкл" : "Звук: выкл"),
                        b -> {
                            runner.setPlaySounds(!runner.playSounds());
                            rebuildWidgets();
                        })
                .bounds(x + fullWidth - halfWidth, y, halfWidth, 18).build();
        addRenderableWidget(sounds);

        // --- низ ---
        addRenderableWidget(Button.builder(Component.literal("Справка по формулам"),
                        b -> minecraft.setScreenAndShow(new FormulaHelpScreen(this)))
                .bounds(leftX, height - 26, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Закрыть"), b -> onClose())
                .bounds(width - 8 - 100, height - 26, 100, 20).build());
    }

    // ---- отрисовка ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(leftX, contentY, leftX + leftWidth, contentY + contentHeight, PANEL_BG);
        graphics.outline(leftX, contentY, leftWidth, contentHeight, PANEL_BORDER);
        graphics.fill(rightX, contentY, rightX + rightWidth, contentY + contentHeight, PANEL_BG);
        graphics.outline(rightX, contentY, rightWidth, contentHeight, PANEL_BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        drawHeader(graphics);
        drawLayerList(graphics, mouseX, mouseY);
        drawSectionLabels(graphics);
    }

    private void drawHeader(GuiGraphicsExtractor graphics) {
        graphics.text(font, Component.literal("Tutorial Schematic").withStyle(ChatFormatting.BOLD), 10, 12, TEXT);

        TutorialSchematic schematic = EditorState.get().schematic();
        String summary = schematic == null
                ? "схема не открыта — начните с «Новая»"
                : "«" + schematic.name() + "» · " + schematic.layerCount() + " слоёв · "
                        + schematic.totalBlocks() + " бл. · ~"
                        + String.format("%.0f", schematic.estimatedSeconds()) + " с";
        graphics.text(font, summary, 10 + font.width("Tutorial Schematic") + 12, 12,
                schematic == null ? TEXT_DIM : OK);
    }

    private void drawLayerList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, Component.literal("Слои"), leftX + 6, contentY + 6, ACCENT);
        graphics.text(font, "порядок сверху вниз = порядок постройки",
                leftX + 6 + font.width("Слои") + 8, contentY + 6, TEXT_DIM);

        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic == null || schematic.layerCount() == 0) {
            graphics.text(font, schematic == null ? "Создайте или откройте схему" : "Слоёв пока нет",
                    leftX + 8, listTop + 6, TEXT_DIM);
            return;
        }

        graphics.enableScissor(leftX + 1, listTop, leftX + leftWidth - 1, listBottom);

        BuildLayer active = EditorState.get().activeLayer();
        int iconsX = leftX + leftWidth - 4 - ICON * ICON_COUNT;
        int rowY = listTop - scroll;

        for (int i = 0; i < schematic.layerCount(); i++) {
            BuildLayer layer = schematic.layerAt(i);
            boolean selected = layer == active;
            boolean hovered = mouseX >= leftX && mouseX < leftX + leftWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseY >= listTop && mouseY < listBottom;

            if (selected) {
                graphics.fill(leftX + 1, rowY, leftX + leftWidth - 1, rowY + ROW_HEIGHT, 0x552F6FA8);
            } else if (hovered) {
                graphics.fill(leftX + 1, rowY, leftX + leftWidth - 1, rowY + ROW_HEIGHT, 0x22FFFFFF);
            }

            // цветная метка — тот же цвет, что и подсветка этого слоя в мире
            graphics.fill(leftX + 4, rowY + 4, leftX + 9, rowY + ROW_HEIGHT - 4, 0xFF000000 | layer.color());

            String name = (i + 1) + ". " + layer.name();
            graphics.text(font, font.plainSubstrByWidth(name, iconsX - leftX - 60), leftX + 13, rowY + 4,
                    selected ? TEXT : (layer.visible() ? TEXT_DIM : HIDDEN));

            String count = String.valueOf(layer.blockCount());
            graphics.text(font, count, iconsX - 6 - font.width(count), rowY + 4, TEXT_DIM);

            drawRowIcons(graphics, iconsX, rowY, i, layer, schematic.layerCount());

            rowY += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    private void drawRowIcons(GuiGraphicsExtractor graphics, int iconsX, int rowY,
                              int index, BuildLayer layer, int layerCount) {
        int centerY = rowY + 4;

        graphics.text(font, "▲", iconsX + 4, centerY, index > 0 ? TEXT : 0xFF3A3F4B);
        graphics.text(font, "▼", iconsX + ICON + 4, centerY, index < layerCount - 1 ? TEXT : 0xFF3A3F4B);

        drawEye(graphics, iconsX + ICON * 2 + 2, rowY + 5, layer.visible());

        boolean confirming = pendingDeleteLayer == index;
        graphics.text(font, "✕", iconsX + ICON * 3 + 4, centerY, confirming ? DANGER : TEXT_DIM);
    }

    /**
     * Глазик видимости слоя: открытый — слой подсвечивается в мире, перечёркнутый — скрыт.
     *
     * <p>Рисуется примитивами, а не символом шрифта: подходящего глифа в игровом шрифте нет,
     * а иконка должна читаться с одного взгляда — при полусотне слоёв по ней кликают чаще
     * всего остального.
     *
     * <p>Занимает 11×7 пикселей от левого верхнего угла {@code (x, y)}.
     */
    private void drawEye(GuiGraphicsExtractor graphics, int x, int y, boolean open) {
        int color = open ? TEXT : HIDDEN;

        // веко: верхняя и нижняя дуги, сходящиеся в уголках
        graphics.fill(x + 3, y, x + 8, y + 1, color);
        graphics.fill(x + 1, y + 1, x + 3, y + 2, color);
        graphics.fill(x + 8, y + 1, x + 10, y + 2, color);
        graphics.fill(x, y + 2, x + 1, y + 5, color);
        graphics.fill(x + 10, y + 2, x + 11, y + 5, color);
        graphics.fill(x + 1, y + 5, x + 3, y + 6, color);
        graphics.fill(x + 8, y + 5, x + 10, y + 6, color);
        graphics.fill(x + 3, y + 6, x + 8, y + 7, color);

        if (open) {
            graphics.fill(x + 4, y + 2, x + 7, y + 5, color);
            return;
        }
        // скрытый слой перечёркиваем; зрачок при этом не рисуем, иначе косая с ним сливается
        for (int row = 0; row < 7; row++) {
            graphics.fill(x + 8 - row, y + row, x + 11 - row, y + row + 1, color);
        }
    }

    private void drawSectionLabels(GuiGraphicsExtractor graphics) {
        int x = rightX + 6;
        // отступы повторяют раскладку кнопок в buildActionButtons: подпись на 12 пикселей
        // выше первой строки своего раздела
        graphics.text(font, Component.literal("Схема"), x, contentY + 8, ACCENT);
        graphics.text(font, Component.literal("Разметка"), x, contentY + 64, ACCENT);
        graphics.text(font, Component.literal("Анимация"), x, contentY + 164, ACCENT);
        graphics.text(font, Component.literal("Постройка"), x, contentY + 198, ACCENT);

        BuildRunner runner = BuildRunner.get();
        String status = switch (runner.state()) {
            case IDLE -> "не запущена";
            case RUNNING -> "идёт, слой " + (runner.currentLayerIndex() + 1)
                    + " — " + Math.round(runner.layerProgress() * 100) + "%";
            case PAUSED -> "на паузе, слой " + (runner.currentLayerIndex() + 1);
            case FINISHED -> "завершена, поставлено " + runner.placedTotal();
        };
        graphics.text(font, status + " · скорость x" + String.format("%.1f", runner.speedMultiplier()),
                x + font.width("Постройка") + 10, contentY + 198, TEXT_DIM);
    }

    private static String shorten(String text, int maxPixels) {
        return Minecraft.getInstance().font.plainSubstrByWidth(text, Math.max(20, maxPixels));
    }

    // ---- ввод ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();

        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic == null || mouseX < leftX || mouseX >= leftX + leftWidth
                || mouseY < listTop || mouseY >= listBottom) {
            pendingDeleteLayer = -1;
            return false;
        }

        int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
        if (index < 0 || index >= schematic.layerCount()) {
            return false;
        }
        BuildLayer layer = schematic.layerAt(index);

        int iconsX = leftX + leftWidth - 4 - ICON * ICON_COUNT;
        if (mouseX >= iconsX) {
            int icon = (int) ((mouseX - iconsX) / ICON);
            handleRowIcon(schematic, layer, index, icon, doubleClick);
            return true;
        }

        pendingDeleteLayer = -1;
        if (doubleClick && layer == EditorState.get().activeLayer()) {
            promptRenameLayer(layer);
        } else {
            EditorState.get().setActiveLayer(layer);
            rebuildWidgets();
        }
        return true;
    }

    private void handleRowIcon(TutorialSchematic schematic, BuildLayer layer, int index, int icon, boolean doubleClick) {
        switch (icon) {
            case 0 -> {
                pendingDeleteLayer = -1;
                if (schematic.moveLayerUp(layer)) {
                    rebuildWidgets();
                }
            }
            case 1 -> {
                pendingDeleteLayer = -1;
                if (schematic.moveLayerDown(layer)) {
                    rebuildWidgets();
                }
            }
            case 2 -> {
                pendingDeleteLayer = -1;
                layer.toggleVisible();
            }
            case 3 -> {
                // первый клик помечает, второй удаляет: слой — это много работы, случайный промах дорог
                if (pendingDeleteLayer == index) {
                    schematic.removeLayer(layer);
                    if (EditorState.get().activeLayer() == layer) {
                        EditorState.get().setActiveLayer(schematic.layerAt(0));
                    }
                    pendingDeleteLayer = -1;
                    rebuildWidgets();
                } else {
                    pendingDeleteLayer = index;
                }
            }
            default -> {
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic != null && mouseX >= leftX && mouseX < leftX + leftWidth) {
            int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
            int maxScroll = Math.max(0, schematic.layerCount() - visibleRows) * ROW_HEIGHT;
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- диалоги ----

    private void promptNewSchematic() {
        minecraft.setScreenAndShow(new TextPromptScreen(this,
                "Название схемы",
                "Так будет называться файл в папке tutorial-schematics",
                "Постройка",
                name -> {
                    EditorState.get().createSchematic(name);
                    EditorState.info("Создана схема «" + name + "». Разметка включена.");
                }));
    }

    private void promptSaveAs() {
        String current = EditorState.get().fileName().replace(SchematicFiles.EXTENSION, "");
        minecraft.setScreenAndShow(new TextPromptScreen(this,
                "Сохранить как",
                "Новое имя файла",
                current,
                name -> {
                    EditorState.get().setFileName(SchematicFiles.sanitize(name) + SchematicFiles.EXTENSION);
                    EditorState.get().save();
                }));
    }

    private void promptNewLayer() {
        minecraft.setScreenAndShow(new TextPromptScreen(this,
                "Название слоя",
                "Например: Фундамент, Стены, Крыша, Интерьер",
                "Слой " + (EditorState.get().schematic().layerCount() + 1),
                name -> {
                    BuildLayer layer = EditorState.get().addLayer(name);
                    EditorState.get().setMarkupEnabled(true);
                    EditorState.info("Слой «" + layer.name() + "» создан. Кликайте ЛКМ по блокам.");
                }));
    }

    private void promptRenameLayer(BuildLayer layer) {
        minecraft.setScreenAndShow(new TextPromptScreen(this,
                "Переименовать слой",
                "Текущее название: " + layer.name(),
                layer.name(),
                layer::setName));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

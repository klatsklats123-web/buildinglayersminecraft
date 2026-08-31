package com.tutorialschematic.client.screen;

import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.ModSettings;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Настройки мода: значения, общие для всех схем.
 *
 * <p>Пока здесь только задержки слоя. Они задаются у каждого слоя отдельно, но выставлять их
 * руками в каждом новом слое утомительно, поэтому тут лежат значения по умолчанию и кнопка,
 * которая разом применяет их ко всем слоям открытой схемы.
 */
public class SettingsScreen extends Screen {

    private static final int PANEL_BG = 0xEE14161C;
    private static final int PANEL_BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int TEXT_DIM = 0xFF9AA0AC;
    private static final int ACCENT = 0xFF5AC8FA;

    private final Screen parent;
    private EditBox startBox;
    private EditBox endBox;
    private EditBox timeBox;
    private String applied = "";

    private int panelX, panelY, panelWidth, panelHeight;

    public SettingsScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Настройки мода"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(360, width - 40);
        panelHeight = 244;
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(30, (height - panelHeight) / 2);

        ModSettings settings = ModSettings.get();
        int fieldX = panelX + panelWidth - 70;
        int y = panelY + 46;

        startBox = numberBox(fieldX, y, settings.defaultStartDelayTicks(), value -> {
            settings.setDefaultStartDelayTicks(value);
            settings.save();
        });
        endBox = numberBox(fieldX, y + 26, settings.defaultEndDelayTicks(), value -> {
            settings.setDefaultEndDelayTicks(value);
            settings.save();
        });

        addRenderableWidget(Button.builder(
                        Component.literal("Применить ко всем слоям схемы"), b -> applyToAll())
                .bounds(panelX + 12, y + 58, panelWidth - 24, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Пропишет эти задержки во все слои открытой схемы. Схему после этого надо сохранить.")))
                .build());

        // Время суток уходит в настройки записи при расстановке камер: за несколько минут
        // постройки солнце успевает уйти, и свет плывёт посреди ролика.
        timeBox = numberBox(fieldX, y + 108, settings.replayTimeOfDay(), value -> {
            settings.setReplayTimeOfDay(value);
            settings.save();
        });
        timeBox.setTooltip(Tooltip.create(Component.literal(
                "Время суток, на котором держать запись при экспорте камер.\n\n"
                        + "0 — рассвет, 6000 — полдень, 12000 — закат, 18000 — полночь.\n\n"
                        + "Минус единица — не трогать, солнце пойдёт своим ходом.")));

        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose())
                .bounds(panelX + panelWidth / 2 - 50, panelY + panelHeight - 28, 100, 20)
                .build());
    }

    private EditBox numberBox(int x, int y, int value, java.util.function.IntConsumer onChange) {
        EditBox box = new EditBox(font, x, y, 58, 18, Component.literal("тиков"));
        box.setValue(String.valueOf(value));
        box.setResponder(text -> {
            try {
                // Минус — часть числа: «не трогать» здесь задают минус единицей.
                String trimmed = text.trim();
                if (trimmed.isEmpty() || "-".equals(trimmed)) {
                    return;
                }
                onChange.accept(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // недописанное число — просто ждём, пока допишут
            }
        });
        addRenderableWidget(box);
        return box;
    }

    private void applyToAll() {
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic == null) {
            applied = "Схема не открыта";
            return;
        }
        ModSettings settings = ModSettings.get();
        for (BuildLayer layer : schematic.layers()) {
            layer.setStartDelayTicks(settings.defaultStartDelayTicks());
            layer.setEndDelayTicks(settings.defaultEndDelayTicks());
        }
        applied = "Применено к " + schematic.layerCount() + " слоям — не забудьте сохранить схему";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);

        graphics.text(font, Component.literal("Настройки мода").withStyle(ChatFormatting.BOLD),
                panelX + 12, panelY + 10, TEXT);
        graphics.text(font, "Задержки по умолчанию для новых слоёв, в тиках (20 тиков = 1 секунда)",
                panelX + 12, panelY + 26, TEXT_DIM);

        int y = panelY + 51;
        graphics.text(font, "Задержка в начале слоя:", panelX + 12, y, TEXT);
        graphics.text(font, "Задержка в конце слоя:", panelX + 12, y + 26, TEXT);

        graphics.fill(panelX + 12, y + 92, panelX + panelWidth - 12, y + 93, PANEL_BORDER);
        graphics.text(font, Component.literal("Съёмка").withStyle(ChatFormatting.BOLD),
                panelX + 12, y + 82, TEXT);
        graphics.text(font, "Время суток:", panelX + 12, y + 113, TEXT);
        graphics.text(font, timeHint(ModSettings.get().replayTimeOfDay()),
                panelX + 12, y + 129, TEXT_DIM);

        if (!applied.isEmpty()) {
            graphics.text(font, applied, panelX + 12, panelY + panelHeight - 46, ACCENT);
        }
    }

    /** Что означает выставленное число — иначе «6000» ни о чём не говорит. */
    private static String timeHint(int time) {
        if (time < 0) {
            return "не трогать — солнце пойдёт своим ходом";
        }
        if (time < 3000) {
            return "утро";
        }
        if (time < 9000) {
            return "полдень — меньше всего резких теней";
        }
        if (time < 13000) {
            return "закат";
        }
        return "ночь";
    }

    @Override
    public void onClose() {
        ModSettings.get().save();
        minecraft.setScreenAndShow(parent);
    }
}

package com.tutorialschematic.client.render;

import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.build.BuildRunner;
import com.tutorialschematic.client.selection.FillMaterial;
import com.tutorialschematic.client.selection.SelectionWand;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Панель состояния в углу экрана.
 *
 * <p>Показывает то, что нужно знать не отрываясь от разметки: какой слой набирается,
 * каким режимом, сколько блоков уже собрано. Во время постройки вместо этого —
 * полоска прогресса по слоям, чтобы во время съёмки было видно, сколько осталось.
 *
 * <p>Панель прячется, когда схема не открыта.
 */
public final class EditorHud {

    private static final int BG = 0xC0101218;
    private static final int BORDER = 0xFF3A3F4B;
    private static final int TEXT = 0xFFE6E8EC;
    private static final int DIM = 0xFF9AA0AC;
    private static final int ACCENT = 0xFF5AC8FA;
    private static final int OK = 0xFF7FD88F;
    private static final int WARN = 0xFFFFC46B;

    private static final int PADDING = 6;
    private static final int LINE = 10;

    private EditorHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(TutorialSchematicMod.MOD_ID, "editor_status"),
                (graphics, deltaTracker) -> render(graphics));
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        EditorState state = EditorState.get();
        TutorialSchematic schematic = state.schematic();

        // F1 обрабатывать не нужно: элементы HUD и так не рисуются со скрытым интерфейсом
        if (schematic == null) {
            return;
        }

        Font font = client.font;
        BuildRunner runner = BuildRunner.get();
        BuildLayer active = state.activeLayer();

        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        lines.add(schematic.name());
        colors.add(ACCENT);

        if (runner.isActive()) {
            BuildLayer building = runner.currentLayer();
            lines.add("Постройка: слой " + (runner.currentLayerIndex() + 1) + "/" + schematic.layerCount()
                    + (building == null ? "" : " — " + building.name()));
            colors.add(TEXT);
            lines.add(Math.round(runner.layerProgress() * 100) + "% · поставлено " + runner.placedTotal()
                    + " · x" + String.format("%.1f", runner.speedMultiplier()));
            colors.add(DIM);
        } else {
            lines.add(active == null
                    ? "Слой не выбран — G, затем «Новый слой»"
                    : "Слой: " + active.name() + " · " + active.blockCount() + " бл.");
            colors.add(active == null ? DIM : TEXT);

            lines.add((state.markupEnabled() ? "Разметка вкл · " : "Разметка выкл · ")
                    + state.mode().displayName() + "  (V — режим)");
            colors.add(state.markupEnabled() ? OK : DIM);

            if (state.markupEnabled()) {
                // без инструмента клики не перехватываются, и это надо сказать прямо:
                // иначе выглядит как будто разметка сломалась
                if (!SelectionWand.isHeldByLocalPlayer()) {
                    lines.add("Возьмите blaze rod в руку · G → Выдать инструмент");
                    colors.add(WARN);
                } else if (state.boxCorner() != null) {
                    // первый угол уже поставлен — важнее всего сказать, что будет дальше
                    lines.add("Первый угол поставлен · ПКМ добавит коробку, ЛКМ уберёт");
                    colors.add(WARN);
                } else {
                    // управление держим на экране: какая кнопка что делает — самый частый вопрос
                    lines.add(state.mode().controls());
                    colors.add(DIM);
                }

                // Включённое заполнение меняет то, что заберёт коробка, поэтому о нём надо
                // говорить всегда: забыть про него легко, а лишний объём в слое заметен не сразу.
                if (state.fillEmpty()) {
                    String material = FillMaterial.heldName();
                    lines.add(material == null
                            ? "Пустота: возьмите блок в ЛЕВУЮ руку"
                            : "Пустота заполняется: " + material);
                    colors.add(material == null ? WARN : OK);
                }
            }
        }

        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        int boxWidth = textWidth + PADDING * 2;
        int boxHeight = lines.size() * LINE + PADDING * 2 + (runner.isActive() ? 5 : 0);

        int x = 4;
        int y = 4;

        graphics.fill(x, y, x + boxWidth, y + boxHeight, BG);
        graphics.outline(x, y, boxWidth, boxHeight, BORDER);

        int lineY = y + PADDING;
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x + PADDING, lineY, colors.get(i));
            lineY += LINE;
        }

        if (runner.isActive()) {
            int barWidth = boxWidth - PADDING * 2;
            graphics.fill(x + PADDING, lineY, x + PADDING + barWidth, lineY + 3, 0xFF2A2E38);
            graphics.fill(x + PADDING, lineY,
                    x + PADDING + (int) (barWidth * runner.layerProgress()), lineY + 3, ACCENT);
        }
    }
}

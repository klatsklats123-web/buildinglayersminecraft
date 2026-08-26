package com.tutorialschematic.client.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.build.BuildRunner;
import com.tutorialschematic.client.flashback.CameraExport;
import com.tutorialschematic.client.flashback.RecordedBuild;
import com.tutorialschematic.client.screen.AnimationEditorScreen;
import com.tutorialschematic.client.selection.SelectionMode;
import com.tutorialschematic.client.selection.SelectionTool;
import com.tutorialschematic.client.selection.SelectionWand;
import com.tutorialschematic.io.SchematicFiles;
import com.tutorialschematic.order.OrderConfig;
import com.tutorialschematic.order.OrderPresets;
import com.tutorialschematic.order.SortKey;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Клиентские команды редактора.
 *
 * <p>Команды дублируют то, что есть в экранах: набирать формулы и двигать слои
 * удобнее мышкой, но команду можно повесить на макрос, а во время съёмки это
 * важнее удобства.
 */
public final class EditorCommands {

    private EditorCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            dispatcher.register(schematicCommand());
            dispatcher.register(layerCommand());
            dispatcher.register(buildCommand());
            dispatcher.register(ClientCommands.literal("anim").executes(ctx -> openEditor()));
            dispatcher.register(posCommand("lpos1", true));
            dispatcher.register(posCommand("lpos2", false));
        });
    }

    // ---- /lpos1, /lpos2 ----

    /**
     * Углы коробки по координатам.
     *
     * <p>Кликом угол можно поставить только на существующий блок: если он приходится
     * на пустоту, прицелиться не во что. Поэтому {@code /lpos1} запоминает первый угол,
     * а {@code /lpos2} ставит второй и сразу применяет коробку. Без координат берётся
     * та клетка, в которой стоит игрок, — удобно просто встать в угол будущей области.
     *
     * <p>{@code /lpos2 remove} тем же жестом убирает коробку из слоя.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> posCommand(String name, boolean first) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal(name)
                .executes(ctx -> handlePos(first, playerPos(), false))
                .then(coords((pos, removing) -> handlePos(first, pos, removing), false));

        if (!first) {
            root = root.then(ClientCommands.literal("remove")
                    .executes(ctx -> handlePos(false, playerPos(), true))
                    .then(coords((pos, removing) -> handlePos(false, pos, removing), true)));
        }
        return root;
    }

    /** Три целых аргумента x y z, общие для обеих команд. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, Integer> coords(
            java.util.function.BiFunction<BlockPos, Boolean, Integer> action, boolean removing) {
        return ClientCommands.argument("x", IntegerArgumentType.integer())
                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> action.apply(new BlockPos(
                                        IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "y"),
                                        IntegerArgumentType.getInteger(ctx, "z")), removing))));
    }

    private static BlockPos playerPos() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? BlockPos.ZERO : client.player.blockPosition();
    }

    private static int handlePos(boolean first, BlockPos pos, boolean removing) {
        EditorState state = EditorState.get();
        if (first) {
            state.setBoxCorner(pos);
            EditorState.info("Первый угол: " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " — теперь /lpos2 по второму");
            return 1;
        }
        BlockPos corner = state.boxCorner();
        if (corner == null) {
            EditorState.error("Сначала задайте первый угол: /lpos1");
            return 0;
        }
        SelectionTool.applyBox(corner, pos, removing);
        state.setBoxCorner(null);
        return 1;
    }

    // ---- /tutorial ----

    private static LiteralArgumentBuilder<FabricClientCommandSource> schematicCommand() {
        return ClientCommands.literal("tutorial")
                .executes(ctx -> info())
                .then(ClientCommands.literal("new")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    EditorState.get().createSchematic(name);
                                    EditorState.info("Создана схема «" + name + "». Разметка включена — "
                                            + "создайте слой: /layer new Фундамент");
                                    return 1;
                                })))
                .then(ClientCommands.literal("open")
                        .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> open(StringArgumentType.getString(ctx, "file")))))
                .then(ClientCommands.literal("save")
                        .executes(ctx -> {
                            EditorState.get().save();
                            return 1;
                        }))
                .then(ClientCommands.literal("saveas")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    EditorState.get().setFileName(
                                            SchematicFiles.sanitize(StringArgumentType.getString(ctx, "name"))
                                                    + SchematicFiles.EXTENSION);
                                    EditorState.get().save();
                                    return 1;
                                })))
                .then(ClientCommands.literal("close")
                        .executes(ctx -> {
                            EditorState.get().close();
                            EditorState.info("Схема закрыта");
                            return 1;
                        }))
                .then(ClientCommands.literal("list")
                        .executes(ctx -> {
                            List<String> files = SchematicFiles.list();
                            if (files.isEmpty()) {
                                EditorState.info("Сохранённых схем пока нет");
                            } else {
                                EditorState.info("Схемы (" + files.size() + "):");
                                files.forEach(f -> EditorState.info("  " + f));
                            }
                            return 1;
                        }))
                .then(ClientCommands.literal("info").executes(ctx -> info()))
                .then(ClientCommands.literal("markup")
                        .then(ClientCommands.literal("on").executes(ctx -> setMarkup(true)))
                        .then(ClientCommands.literal("off").executes(ctx -> setMarkup(false))))
                .then(ClientCommands.literal("mode")
                        .executes(ctx -> {
                            EditorState.get().cycleMode();
                            EditorState.info("Режим выделения: " + EditorState.get().mode().displayName()
                                    + " — " + EditorState.get().mode().hint());
                            return 1;
                        })
                        .then(ClientCommands.argument("mode", StringArgumentType.word())
                                .executes(ctx -> setMode(StringArgumentType.getString(ctx, "mode")))))
                .then(ClientCommands.literal("wand")
                        .executes(ctx -> {
                            SelectionWand.give();
                            return 1;
                        }))
                .then(ClientCommands.literal("show")
                        .then(ClientCommands.literal("all").executes(ctx -> {
                            EditorState.get().setShowOnlyActiveLayer(false);
                            EditorState.info("Показаны все слои");
                            return 1;
                        }))
                        .then(ClientCommands.literal("active").executes(ctx -> {
                            EditorState.get().setShowOnlyActiveLayer(true);
                            EditorState.info("Показан только активный слой");
                            return 1;
                        })))
                .then(ClientCommands.literal("highlights")
                        .executes(ctx -> {
                            EditorState state = EditorState.get();
                            state.setHighlightsVisible(!state.highlightsVisible());
                            EditorState.info("Подсветка: " + (state.highlightsVisible() ? "вкл" : "выкл"));
                            return 1;
                        }))
                .then(ClientCommands.literal("record")
                        .executes(ctx -> {
                            RecordedBuild.startRecordingThenBuild();
                            return 1;
                        }))
                .then(ClientCommands.literal("cameras")
                        .executes(ctx -> {
                            CameraExport.exportForNewestReplay();
                            return 1;
                        }))
                .then(ClientCommands.literal("autoclear")
                        .executes(ctx -> {
                            BuildRunner.get().setAutoClear(!EditorState.get().autoClear());
                            return 1;
                        }));
    }

    // ---- /layer ----

    private static LiteralArgumentBuilder<FabricClientCommandSource> layerCommand() {
        return ClientCommands.literal("layer")
                .executes(ctx -> listLayers())
                .then(ClientCommands.literal("new")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    if (!requireSchematic()) {
                                        return 0;
                                    }
                                    BuildLayer layer = EditorState.get().addLayer(StringArgumentType.getString(ctx, "name"));
                                    EditorState.info("Слой «" + layer.name() + "» создан и выбран. "
                                            + "Кликайте ЛКМ по блокам, Shift+ЛКМ — убрать.");
                                    return 1;
                                })))
                .then(ClientCommands.literal("list").executes(ctx -> listLayers()))
                .then(ClientCommands.literal("select")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                    EditorState.get().setActiveLayer(layer);
                                    EditorState.info("Активный слой: «" + layer.name() + "»");
                                }))))
                .then(ClientCommands.literal("rename")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                            layer.setName(StringArgumentType.getString(ctx, "name"));
                                            EditorState.info("Переименовано в «" + layer.name() + "»");
                                        })))))
                .then(ClientCommands.literal("delete")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                    EditorState state = EditorState.get();
                                    state.schematic().removeLayer(layer);
                                    if (state.activeLayer() == layer) {
                                        state.setActiveLayer(state.schematic().layerAt(0));
                                    }
                                    EditorState.info("Слой «" + layer.name() + "» удалён");
                                }))))
                .then(ClientCommands.literal("up")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                    if (EditorState.get().schematic().moveLayerUp(layer)) {
                                        EditorState.info("«" + layer.name() + "» строится раньше");
                                    } else {
                                        EditorState.error("Слой уже первый");
                                    }
                                }))))
                .then(ClientCommands.literal("down")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                    if (EditorState.get().schematic().moveLayerDown(layer)) {
                                        EditorState.info("«" + layer.name() + "» строится позже");
                                    } else {
                                        EditorState.error("Слой уже последний");
                                    }
                                }))))
                .then(ClientCommands.literal("clear")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                    int count = layer.blockCount();
                                    layer.clear();
                                    EditorState.info("Из «" + layer.name() + "» убрано " + count + " бл.");
                                }))))
                .then(ClientCommands.literal("pause")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, 1200))
                                        .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                            int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                            layer.setPauseAfterTicks(ticks);
                                            EditorState.info("Пауза после слоя: " + ticks + " тиков ("
                                                    + String.format("%.1f", ticks / 20.0) + " с)");
                                        })))))
                .then(animationCommand());
    }

    /** {@code /layer anim ...} — то же, что экран редактора, но текстом. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> animationCommand() {
        return ClientCommands.literal("anim")
                .then(ClientCommands.literal("presets")
                        .executes(ctx -> {
                            EditorState.info("Готовые анимации:");
                            for (OrderPresets.Preset preset : OrderPresets.all()) {
                                EditorState.info("  " + preset.name() + " — " + String.join(", ", preset.formulas()));
                            }
                            return 1;
                        }))
                .then(ClientCommands.literal("preset")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            OrderPresets.Preset preset = OrderPresets.byName(name);
                                            if (preset == null) {
                                                EditorState.error("Нет пресета «" + name + "». Список: /layer anim presets");
                                                return;
                                            }
                                            preset.applyTo(layer.order());
                                            layer.invalidateOrder();
                                            EditorState.info("«" + layer.name() + "» → " + preset.name()
                                                    + ": " + layer.order().describe());
                                        })))))
                .then(ClientCommands.literal("formula")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, OrderConfig.MAX_KEYS))
                                        .then(ClientCommands.argument("formula", StringArgumentType.greedyString())
                                                .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                                    int level = IntegerArgumentType.getInteger(ctx, "level") - 1;
                                                    String formula = StringArgumentType.getString(ctx, "formula");
                                                    OrderConfig order = layer.order();
                                                    while (order.keys().size() <= level && order.canAddKey()) {
                                                        order.addKey("y");
                                                    }
                                                    SortKey key = order.key(level);
                                                    if (key == null) {
                                                        EditorState.error("Нет такого уровня сортировки");
                                                        return;
                                                    }
                                                    if (key.setSource(formula)) {
                                                        layer.invalidateOrder();
                                                        EditorState.info("Уровень " + (level + 1) + " → " + formula
                                                                + " | " + order.describe());
                                                    } else {
                                                        EditorState.error("Ошибка в формуле: " + key.error());
                                                    }
                                                }))))))
                .then(ClientCommands.literal("reverse")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, OrderConfig.MAX_KEYS))
                                        .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                            SortKey key = layer.order().key(IntegerArgumentType.getInteger(ctx, "level") - 1);
                                            if (key == null) {
                                                EditorState.error("Нет такого уровня сортировки");
                                                return;
                                            }
                                            key.toggleDescending();
                                            layer.invalidateOrder();
                                            EditorState.info("Уровень: " + key);
                                        })))))
                .then(ClientCommands.literal("batch")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("size", IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                            layer.order().setBatchSize(IntegerArgumentType.getInteger(ctx, "size"));
                                            EditorState.info("Блоков за шаг: " + layer.order().batchSize()
                                                    + " (" + String.format("%.1f", layer.order().blocksPerSecond()) + " бл/с)");
                                        })))))
                .then(ClientCommands.literal("speed")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, 200))
                                        .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                            layer.order().setTicksPerStep(IntegerArgumentType.getInteger(ctx, "ticks"));
                                            EditorState.info("Пауза между шагами: " + layer.order().ticksPerStep()
                                                    + " тиков (" + String.format("%.1f", layer.order().blocksPerSecond()) + " бл/с)");
                                        })))))
                .then(ClientCommands.literal("seed")
                        .then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> withLayer(ctx.getArgument("id", Integer.class), layer -> {
                                    layer.order().rerollSeed();
                                    layer.invalidateOrder();
                                    EditorState.info("Новое семя случайности: " + layer.order().seed());
                                }))));
    }

    // ---- /build ----

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildCommand() {
        return ClientCommands.literal("build")
                .executes(ctx -> buildStatus())
                .then(ClientCommands.literal("clear")
                        .executes(ctx -> {
                            BuildRunner.get().clearAll();
                            return 1;
                        }))
                .then(ClientCommands.literal("start")
                        .executes(ctx -> {
                            BuildRunner.get().start();
                            return 1;
                        }))
                .then(ClientCommands.literal("pause")
                        .executes(ctx -> {
                            BuildRunner.get().pause();
                            return 1;
                        }))
                .then(ClientCommands.literal("resume")
                        .executes(ctx -> {
                            BuildRunner.get().resume();
                            return 1;
                        }))
                .then(ClientCommands.literal("stop")
                        .executes(ctx -> {
                            BuildRunner.get().stop();
                            return 1;
                        }))
                .then(ClientCommands.literal("finish")
                        .executes(ctx -> {
                            BuildRunner.get().finishInstantly();
                            return 1;
                        }))
                .then(ClientCommands.literal("rewind")
                        .then(ClientCommands.argument("layer", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    BuildRunner.get().rewindTo(IntegerArgumentType.getInteger(ctx, "layer") - 1);
                                    return 1;
                                })))
                .then(ClientCommands.literal("from")
                        .then(ClientCommands.argument("layer", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    BuildRunner.get().startFromLayer(IntegerArgumentType.getInteger(ctx, "layer") - 1, true);
                                    return 1;
                                })))
                .then(ClientCommands.literal("speed")
                        .then(ClientCommands.argument("multiplier", DoubleArgumentType.doubleArg(0.1, 20.0))
                                .executes(ctx -> {
                                    double value = DoubleArgumentType.getDouble(ctx, "multiplier");
                                    BuildRunner.get().setSpeedMultiplier(value);
                                    EditorState.info("Скорость: x" + BuildRunner.get().speedMultiplier());
                                    return 1;
                                })))
                .then(ClientCommands.literal("sounds")
                        .executes(ctx -> {
                            BuildRunner runner = BuildRunner.get();
                            runner.setPlaySounds(!runner.playSounds());
                            EditorState.info("Звук установки блоков: " + (runner.playSounds() ? "вкл" : "выкл"));
                            return 1;
                        }));
    }

    // ---- вспомогательное ----

    private interface LayerAction {
        void run(BuildLayer layer);
    }

    /** Находит слой по номеру в списке (как показано в /layer list) и выполняет действие. */
    private static int withLayer(int number, LayerAction action) {
        if (!requireSchematic()) {
            return 0;
        }
        TutorialSchematic schematic = EditorState.get().schematic();
        BuildLayer layer = schematic.layerAt(number - 1);
        if (layer == null) {
            EditorState.error("Нет слоя " + number + ". Всего слоёв: " + schematic.layerCount());
            return 0;
        }
        action.run(layer);
        return 1;
    }

    /** Открывает экран редактора анимации для активного слоя. */
    private static int openEditor() {
        if (!requireSchematic()) {
            return 0;
        }
        EditorState state = EditorState.get();
        BuildLayer layer = state.activeLayer();
        if (layer == null) {
            layer = state.schematic().layerAt(0);
            state.setActiveLayer(layer);
        }
        if (layer == null) {
            EditorState.error("В схеме нет слоёв: /layer new <название>");
            return 0;
        }
        BuildLayer target = layer;
        // экран открываем следующим тиком: сейчас ещё закрывается чат, из которого пришла команда
        Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().setScreenAndShow(new AnimationEditorScreen(target)));
        return 1;
    }

    private static boolean requireSchematic() {
        if (!EditorState.get().hasSchematic()) {
            EditorState.error("Сначала создайте или откройте схему: /tutorial new <название>");
            return false;
        }
        return true;
    }

    private static int setMarkup(boolean enabled) {
        EditorState.get().setMarkupEnabled(enabled);
        EditorState.info("Разметка: " + (enabled ? "вкл — ЛКМ добавляет блоки в активный слой" : "выкл"));
        return 1;
    }

    private static int setMode(String name) {
        for (SelectionMode mode : SelectionMode.values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                EditorState.get().setMode(mode);
                EditorState.info("Режим выделения: " + mode.displayName() + " — " + mode.hint());
                return 1;
            }
        }
        StringBuilder available = new StringBuilder();
        for (SelectionMode mode : SelectionMode.values()) {
            if (!available.isEmpty()) {
                available.append(", ");
            }
            available.append(mode.name().toLowerCase());
        }
        EditorState.error("Неизвестный режим. Доступны: " + available);
        return 0;
    }

    private static int open(String file) {
        try {
            TutorialSchematic schematic = SchematicFiles.load(file, null);
            EditorState.get().openSchematic(schematic, file);
            EditorState.info("Открыто: " + schematic);
            EditorState.get().warnIfForeignWorld(schematic);
            return 1;
        } catch (Exception e) {
            EditorState.error("Не удалось открыть: " + e.getMessage());
            return 0;
        }
    }

    private static int listLayers() {
        if (!requireSchematic()) {
            return 0;
        }
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic.layerCount() == 0) {
            EditorState.info("Слоёв пока нет: /layer new <название>");
            return 1;
        }
        EditorState.info("Слои (порядок постройки):");
        for (int i = 0; i < schematic.layerCount(); i++) {
            BuildLayer layer = schematic.layerAt(i);
            String marker = layer == EditorState.get().activeLayer() ? " ← активный" : "";
            EditorState.info("  " + (i + 1) + ". " + layer.name() + " — " + layer.blockCount()
                    + " бл. | " + layer.order().describe() + marker);
        }
        return 1;
    }

    private static int info() {
        EditorState state = EditorState.get();
        if (!state.hasSchematic()) {
            EditorState.info("Схема не открыта. /tutorial new <название> — создать, /tutorial list — список файлов");
            return 1;
        }
        TutorialSchematic schematic = state.schematic();
        int[] size = schematic.size();
        EditorState.info("Схема «" + schematic.name() + "» (" + state.fileName() + ")");
        EditorState.info("  Слоёв: " + schematic.layerCount() + ", блоков: " + schematic.totalBlocks());
        EditorState.info("  Размер: " + size[0] + " x " + size[1] + " x " + size[2]);
        EditorState.info("  Примерная длительность: " + String.format("%.1f", schematic.estimatedSeconds()) + " с");
        EditorState.info("  Разметка: " + (state.markupEnabled() ? "вкл" : "выкл")
                + ", режим: " + state.mode().displayName() + " — " + state.mode().controls());
        return 1;
    }

    private static int buildStatus() {
        BuildRunner runner = BuildRunner.get();
        BuildLayer layer = runner.currentLayer();
        EditorState.info("Постройка: " + switch (runner.state()) {
            case IDLE -> "не запущена";
            case RUNNING -> "идёт";
            case PAUSED -> "на паузе";
            case FINISHED -> "завершена";
        });
        if (layer != null) {
            EditorState.info("  Слой " + (runner.currentLayerIndex() + 1) + " «" + layer.name() + "», прогресс "
                    + Math.round(runner.layerProgress() * 100) + "%");
        }
        EditorState.info("  Скорость: x" + runner.speedMultiplier() + ", поставлено: " + runner.placedTotal());
        return 1;
    }
}

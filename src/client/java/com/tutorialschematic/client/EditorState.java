package com.tutorialschematic.client;

import com.tutorialschematic.client.selection.SelectionMode;
import com.tutorialschematic.io.SchematicFiles;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Всё состояние редактора: какая схема открыта, какой слой сейчас набирается
 * и каким способом выделяем.
 *
 * <p>Один экземпляр на игру — редактируется всегда одна схема, и держать её в
 * одном месте проще, чем таскать через все экраны и обработчики ввода.
 */
public final class EditorState {

    private static final EditorState INSTANCE = new EditorState();

    private EditorState() {
    }

    public static EditorState get() {
        return INSTANCE;
    }

    private TutorialSchematic schematic;
    private String fileName;
    private BuildLayer activeLayer;

    private SelectionMode mode = SelectionMode.SINGLE;
    private BlockPos boxCorner;

    private boolean markupEnabled;
    private boolean showOnlyActiveLayer;
    private boolean highlightsVisible = true;

    // ---- схема ----

    @Nullable
    public TutorialSchematic schematic() {
        return schematic;
    }

    public boolean hasSchematic() {
        return schematic != null;
    }

    public String fileName() {
        return fileName == null ? "" : fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public TutorialSchematic createSchematic(String name) {
        this.schematic = new TutorialSchematic(name);
        this.schematic.setAuthor(Minecraft.getInstance().getUser().getName());
        this.fileName = SchematicFiles.sanitize(name) + SchematicFiles.EXTENSION;
        this.activeLayer = null;
        this.boxCorner = null;
        this.markupEnabled = true;
        return this.schematic;
    }

    public void openSchematic(TutorialSchematic schematic, String fileName) {
        this.schematic = schematic;
        this.fileName = fileName;
        this.activeLayer = schematic.layerCount() > 0 ? schematic.layerAt(0) : null;
        this.boxCorner = null;
    }

    public void close() {
        this.schematic = null;
        this.activeLayer = null;
        this.fileName = null;
        this.boxCorner = null;
        this.markupEnabled = false;
    }

    /** Сохраняет открытую схему. Возвращает путь к файлу либо {@code null} при ошибке. */
    @Nullable
    public Path save() {
        if (schematic == null) {
            error("Нет открытой схемы");
            return null;
        }
        try {
            Path path = SchematicFiles.save(schematic, fileName);
            this.fileName = path.getFileName().toString();
            info("Сохранено: " + path.getFileName() + " (" + schematic.totalBlocks() + " бл. в "
                    + schematic.layerCount() + " слоях)");
            return path;
        } catch (IOException e) {
            error("Не удалось сохранить: " + e.getMessage());
            return null;
        }
    }

    // ---- активный слой ----

    @Nullable
    public BuildLayer activeLayer() {
        return activeLayer;
    }

    public void setActiveLayer(@Nullable BuildLayer layer) {
        this.activeLayer = layer;
        this.boxCorner = null;
    }

    public BuildLayer addLayer(String name) {
        if (schematic == null) {
            return null;
        }
        BuildLayer layer = schematic.addLayer(name);
        this.activeLayer = layer;
        this.boxCorner = null;
        return layer;
    }

    // ---- режим выделения ----

    public SelectionMode mode() {
        return mode;
    }

    public void setMode(SelectionMode mode) {
        this.mode = mode;
        this.boxCorner = null;
    }

    public void cycleMode() {
        setMode(mode.next());
    }

    @Nullable
    public BlockPos boxCorner() {
        return boxCorner;
    }

    public void setBoxCorner(@Nullable BlockPos pos) {
        this.boxCorner = pos;
    }

    // ---- отображение ----

    /** Реагирует ли редактор на клики по блокам. */
    public boolean markupEnabled() {
        return markupEnabled;
    }

    public void setMarkupEnabled(boolean enabled) {
        this.markupEnabled = enabled;
        if (!enabled) {
            this.boxCorner = null;
        }
    }

    public boolean showOnlyActiveLayer() {
        return showOnlyActiveLayer;
    }

    public void setShowOnlyActiveLayer(boolean value) {
        this.showOnlyActiveLayer = value;
    }

    public boolean highlightsVisible() {
        return highlightsVisible;
    }

    public void setHighlightsVisible(boolean value) {
        this.highlightsVisible = value;
    }

    /** Все блоки схемы — для быстрой проверки «этот блок уже размечен». */
    public Set<BlockPos> allMarkedPositions() {
        Set<BlockPos> all = new HashSet<>();
        if (schematic != null) {
            for (BuildLayer layer : schematic.layers()) {
                all.addAll(layer.blocks().keySet());
            }
        }
        return all;
    }

    // ---- сообщения ----

    public static void info(String text) {
        send(Component.literal("[Схема] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    public static void error(String text) {
        send(Component.literal("[Схема] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.RED)));
    }

    /** Короткое сообщение над хотбаром — не засоряет чат при частых действиях. */
    public static void actionBar(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void send(Component component) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(component);
        }
    }
}

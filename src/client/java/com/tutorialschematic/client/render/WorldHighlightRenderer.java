package com.tutorialschematic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.selection.SelectionTool;
import com.tutorialschematic.client.selection.SelectionWand;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.EntityData;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Подсветка размеченных блоков прямо в мире.
 *
 * <p>Рисуются только внешние грани: у сплошной стены внутренние грани всё равно
 * не видно, а геометрии они дают в разы больше. Благодаря этому подсветка
 * постройки на десятки тысяч блоков остаётся дешёвой.
 *
 * <p>Рамки вокруг слоя и схемы — это не линии, а очень тонкие коробки. Так весь
 * рендер укладывается в один тип отрисовки с одним форматом вершин: у линий в
 * новом пайплайне свой набор обязательных атрибутов, и любое расхождение с ним
 * роняет кадр целиком.
 */
public final class WorldHighlightRenderer {

    /** Потолок на количество подсвечиваемых блоков за кадр — страховка от просадки FPS. */
    private static final int RENDER_LIMIT = 60000;

    /** Насколько грань отодвинута от поверхности блока, чтобы не мерцать с ней. */
    private static final float INSET = 0.002f;

    /** Толщина рёбер рамки в долях блока. */
    private static final float EDGE = 0.03f;

    private static final int FILL_ALPHA = 90;
    private static final int CURSOR_ALPHA = 140;

    /** Об ошибке отрисовки сообщаем один раз, иначе чат зальёт по сообщению на кадр. */
    private static boolean failureReported;

    private WorldHighlightRenderer() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(WorldHighlightRenderer::render);
    }

    private static void render(LevelRenderContext context) {
        EditorState state = EditorState.get();
        Minecraft client = Minecraft.getInstance();
        TutorialSchematic schematic = state.schematic();

        if (schematic == null || !state.highlightsVisible() || client.level == null) {
            return;
        }

        Vec3 camera = client.gameRenderer.mainCamera().position();
        PoseStack pose = context.poseStack();

        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        Set<BlockPos> cursor = cursorPositions(state);

        context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugFilledBox(), (p, consumer) -> {
            // Ошибка внутри отрисовки уронила бы весь кадр, а с ним игру. Лучше погасить
            // подсветку и сказать об этом: разметка и постройка от неё не зависят.
            try {
                draw(p, consumer, state, schematic, cursor);
            } catch (RuntimeException e) {
                state.setHighlightsVisible(false);
                if (!failureReported) {
                    failureReported = true;
                    TutorialSchematicMod.LOGGER.error("Подсветка отключена из-за ошибки отрисовки", e);
                    EditorState.error("Подсветка отключена из-за ошибки отрисовки — подробности в логе. "
                            + "Разметка и постройка работают.");
                }
            }
        });

        pose.popPose();
    }

    private static void draw(PoseStack.Pose pose, VertexConsumer consumer, EditorState state,
                             TutorialSchematic schematic, Set<BlockPos> cursor) {
        int drawn = 0;
        for (BuildLayer layer : schematic.layers()) {
            if (!isLayerShown(state, layer)) {
                continue;
            }
            Set<BlockPos> positions = layer.blocks().keySet();
            int color = layer.color();
            for (BlockPos pos : positions) {
                if (drawn++ > RENDER_LIMIT) {
                    break;
                }
                emitOuterFaces(pose, consumer, pos, positions, color, FILL_ALPHA);
            }
            // декорации подсвечиваем рамкой по их клетке: у картины и рамки своя форма,
            // а рамка в цвет слоя сразу говорит, что декорация в схеме учтена
            for (EntityData data : layer.entities().values()) {
                BlockPos pos = data.blockPos();
                emitBoxOutline(pose, consumer, pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX(), pos.getY(), pos.getZ(), color, 200);
            }
        }

        // блоки, которые захватит клик прямо сейчас — ярче остального
        for (BlockPos pos : cursor) {
            emitBlockFaces(pose, consumer, pos, 0xFFFFFF, CURSOR_ALPHA);
        }
        // рамку вокруг блока рисуем только когда он один: у коробки из сотен блоков
        // двенадцать рёбер на каждый — это десятки тысяч лишних граней за кадр
        if (cursor.size() == 1) {
            BlockPos pos = cursor.iterator().next();
            emitBoxOutline(pose, consumer, pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX(), pos.getY(), pos.getZ(), 0xFFFFFF, 220);
        }

        BuildLayer active = state.activeLayer();
        if (active != null) {
            int[] bounds = active.bounds();
            if (bounds != null) {
                emitBoxOutline(pose, consumer, bounds[0], bounds[1], bounds[2],
                        bounds[3], bounds[4], bounds[5], active.color(), 255);
            }
        }

        int[] all = schematic.bounds();
        if (all != null) {
            emitBoxOutline(pose, consumer, all[0], all[1], all[2], all[3], all[4], all[5], 0xFFFFFF, 110);
        }

        // Режим «Две точки»: тянем зелёную рамку от первого угла к прицелу. Без неё
        // масштаб будущего выделения виден только после того, как второй клик его
        // применил, — а коробка забирает всё непустое в объёме, включая землю под домом.
        BlockPos corner = state.boxCorner();
        if (corner != null) {
            BlockPos to = lookedAtBlock();
            if (to == null) {
                to = corner;
            }
            emitBoxOutline(pose, consumer,
                    Math.min(corner.getX(), to.getX()),
                    Math.min(corner.getY(), to.getY()),
                    Math.min(corner.getZ(), to.getZ()),
                    Math.max(corner.getX(), to.getX()),
                    Math.max(corner.getY(), to.getY()),
                    Math.max(corner.getZ(), to.getZ()),
                    0x00FF00, 255);
        }
    }

    private static boolean isLayerShown(EditorState state, BuildLayer layer) {
        if (!layer.visible()) {
            return false;
        }
        return !state.showOnlyActiveLayer() || layer == state.activeLayer();
    }

    /** Блоки под прицелом, которые захватит клик при текущем режиме выделения. */
    private static Set<BlockPos> cursorPositions(EditorState state) {
        Minecraft client = Minecraft.getInstance();
        // мышь захвачена только когда игрок в мире, а не в каком-нибудь экране
        if (!state.markupEnabled() || client.level == null || !client.mouseHandler.isMouseGrabbed()) {
            return Set.of();
        }
        // без инструмента в руке клик ничего не выделит — незачем и подсвечивать
        if (!SelectionWand.isHeld(client.player)) {
            return Set.of();
        }
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return Set.of();
        }
        Set<BlockPos> positions = SelectionTool.previewPositions(client.level, blockHit.getBlockPos());
        return positions.size() > 4096 ? new HashSet<>(Set.of(blockHit.getBlockPos())) : positions;
    }

    /** Блок под прицелом либо {@code null}, если игрок смотрит в пустоту. */
    @Nullable
    private static BlockPos lookedAtBlock() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    // ---- геометрия ----

    /** Рисует только те грани блока, у которых нет соседа из того же набора. */
    private static void emitOuterFaces(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos,
                                       Set<BlockPos> siblings, int rgb, int alpha) {
        for (Direction direction : Direction.values()) {
            if (siblings.contains(pos.relative(direction))) {
                continue;
            }
            emitFace(pose, consumer, pos, direction, rgb, alpha);
        }
    }

    private static void emitBlockFaces(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos, int rgb, int alpha) {
        for (Direction direction : Direction.values()) {
            emitFace(pose, consumer, pos, direction, rgb, alpha);
        }
    }

    private static void emitFace(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos,
                                 Direction direction, int rgb, int alpha) {
        float x0 = pos.getX() - INSET, y0 = pos.getY() - INSET, z0 = pos.getZ() - INSET;
        float x1 = pos.getX() + 1 + INSET, y1 = pos.getY() + 1 + INSET, z1 = pos.getZ() + 1 + INSET;

        int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;

        switch (direction) {
            case DOWN -> quad(pose, consumer, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, red, green, blue, alpha);
            case UP -> quad(pose, consumer, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, red, green, blue, alpha);
            case NORTH -> quad(pose, consumer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, red, green, blue, alpha);
            case SOUTH -> quad(pose, consumer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, red, green, blue, alpha);
            case WEST -> quad(pose, consumer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, red, green, blue, alpha);
            case EAST -> quad(pose, consumer, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, red, green, blue, alpha);
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             int red, int green, int blue, int alpha) {
        consumer.addVertex(pose, ax, ay, az).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, bx, by, bz).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, cx, cy, cz).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, dx, dy, dz).setColor(red, green, blue, alpha);
    }

    /**
     * Рамка вокруг области блоков (границы включительно): двенадцать рёбер,
     * каждое — тонкая коробка.
     */
    private static void emitBoxOutline(PoseStack.Pose pose, VertexConsumer consumer,
                                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                       int rgb, int alpha) {
        float x0 = minX, y0 = minY, z0 = minZ;
        float x1 = maxX + 1, y1 = maxY + 1, z1 = maxZ + 1;
        float t = EDGE;

        for (float y : new float[]{y0, y1}) {
            for (float z : new float[]{z0, z1}) {
                box(pose, consumer, x0 - t, y - t, z - t, x1 + t, y + t, z + t, rgb, alpha);
            }
        }
        for (float x : new float[]{x0, x1}) {
            for (float z : new float[]{z0, z1}) {
                box(pose, consumer, x - t, y0 - t, z - t, x + t, y1 + t, z + t, rgb, alpha);
            }
        }
        for (float x : new float[]{x0, x1}) {
            for (float y : new float[]{y0, y1}) {
                box(pose, consumer, x - t, y - t, z0 - t, x + t, y + t, z1 + t, rgb, alpha);
            }
        }
    }

    /** Сплошная коробка по произвольным границам — шесть граней. */
    private static void box(PoseStack.Pose pose, VertexConsumer consumer,
                            float x0, float y0, float z0, float x1, float y1, float z1,
                            int rgb, int alpha) {
        int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;

        quad(pose, consumer, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, red, green, blue, alpha);
        quad(pose, consumer, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, red, green, blue, alpha);
        quad(pose, consumer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, red, green, blue, alpha);
        quad(pose, consumer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, red, green, blue, alpha);
        quad(pose, consumer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, red, green, blue, alpha);
        quad(pose, consumer, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, red, green, blue, alpha);
    }
}

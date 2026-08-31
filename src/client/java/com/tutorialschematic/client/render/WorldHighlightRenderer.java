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

    /**
     * Насколько грань подсветки отодвинута от поверхности блока.
     *
     * <p>Отступ растёт с расстоянием, и это не украшательство. Буфер глубины хранит не саму
     * дальность, а величину, точность которой падает квадратично с расстоянием: у самого лица
     * различимы тысячные доли блока, а в полусотне блоков — уже сотые. Постоянный отступ в
     * {@code 0.002} у ног работал, а вдали оказывался мельче шага буфера, и грань подсветки
     * начинала спорить с гранью самого блока — то одна впереди, то другая. Это и есть то
     * самое мерцание.
     */
    private static final float INSET_NEAR = 0.004f;
    private static final float INSET_PER_BLOCK = 0.0007f;
    private static final float INSET_MAX = 0.06f;

    /** Толщина рёбер рамки в долях блока. */
    private static final float EDGE = 0.03f;

    /**
     * Толщина ободка по краю подсвеченной поверхности.
     *
     * <p>Ободок и делает картинку похожей на Litematica: там читается не сплошная заливка, а
     * очертания. Рисуется он только по краю области — там, где соседнего размеченного блока
     * нет, — поэтому сплошная стена остаётся одной заливкой с рамкой по периметру, а не сеткой
     * из тысяч клеток.
     */
    private static final float RIM = 0.06f;

    /** Заливка слабее ободка: цвет должен подсказывать, а не закрашивать постройку. */
    private static final int FILL_ALPHA = 60;
    private static final int RIM_ALPHA = 190;
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
                draw(p, consumer, state, schematic, cursor, camera);
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
                             TutorialSchematic schematic, Set<BlockPos> cursor, Vec3 camera) {
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
                emitOuterFaces(pose, consumer, pos, positions, color, FILL_ALPHA, camera);
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
            emitBlockFaces(pose, consumer, pos, 0xFFFFFF, CURSOR_ALPHA, camera);
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

    /**
     * Рисует только те грани блока, у которых нет соседа из того же набора, и обводит края
     * подсвеченной поверхности.
     *
     * <p>Ободок ставится лишь там, где область кончается: у блока посреди стены соседи есть со
     * всех сторон, и он остаётся чистой заливкой. Поэтому стена читается как один цветной
     * прямоугольник с чёткой каймой, а не как сетка из клеток — и стоит это считанных
     * дополнительных граней, а не четырёх на каждый блок.
     */
    private static void emitOuterFaces(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos,
                                       Set<BlockPos> siblings, int rgb, int alpha, Vec3 camera) {
        float inset = insetFor(camera, pos);
        for (Direction direction : Direction.values()) {
            if (siblings.contains(pos.relative(direction))) {
                continue;
            }
            emitFace(pose, consumer, pos, direction, rgb, alpha, inset);
            emitRim(pose, consumer, pos, direction, siblings, rgb, inset);
        }
    }

    private static void emitBlockFaces(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos,
                                       int rgb, int alpha, Vec3 camera) {
        float inset = insetFor(camera, pos);
        for (Direction direction : Direction.values()) {
            emitFace(pose, consumer, pos, direction, rgb, alpha, inset);
        }
    }

    /** Отступ тем больше, чем дальше блок: вблизи хватает тысячных, вдали нужны сотые. */
    private static float insetFor(Vec3 camera, BlockPos pos) {
        double dx = pos.getX() + 0.5 - camera.x;
        double dy = pos.getY() + 0.5 - camera.y;
        double dz = pos.getZ() + 0.5 - camera.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) Math.min(INSET_MAX, INSET_NEAR + distance * INSET_PER_BLOCK);
    }

    /**
     * Одна грань подсветки.
     *
     * <p>Отступ идёт <b>только вдоль нормали</b> самой грани, а по двум другим осям грань
     * повторяет блок ровно. Раньше коробка раздувалась во все стороны сразу, и у двух
     * соседних размеченных блоков их верхние грани налезали друг на друга, оставаясь при
     * этом в одной плоскости, — то есть спорили за глубину уже между собой. Это и было
     * мерцание внутри области: по краям, где соседей нет, ничего не мигало, а в середине
     * сплошной стены мигало всегда. Чем больше был отступ, тем шире налезание и тем заметнее
     * мерцание.
     */
    private static void emitFace(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos,
                                 Direction direction, int rgb, int alpha, float inset) {
        float x0 = pos.getX(), y0 = pos.getY(), z0 = pos.getZ();
        float x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;

        int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;

        switch (direction) {
            case DOWN -> {
                float y = y0 - inset;
                quad(pose, consumer, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, red, green, blue, alpha);
            }
            case UP -> {
                float y = y1 + inset;
                quad(pose, consumer, x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, red, green, blue, alpha);
            }
            case NORTH -> {
                float z = z0 - inset;
                quad(pose, consumer, x0, y0, z, x0, y1, z, x1, y1, z, x1, y0, z, red, green, blue, alpha);
            }
            case SOUTH -> {
                float z = z1 + inset;
                quad(pose, consumer, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, red, green, blue, alpha);
            }
            case WEST -> {
                float x = x0 - inset;
                quad(pose, consumer, x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0, red, green, blue, alpha);
            }
            case EAST -> {
                float x = x1 + inset;
                quad(pose, consumer, x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1, red, green, blue, alpha);
            }
        }
    }

    /**
     * Обводит грань там, где подсвеченная поверхность обрывается.
     *
     * <p>Для каждой из четырёх сторон грани смотрим соседа в ту сторону: если он тоже
     * размечен, поверхность продолжается и обводить нечего. Ободок кладётся чуть дальше
     * заливки, иначе они окажутся в одной плоскости и заспорят между собой.
     */
    private static void emitRim(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos,
                                Direction face, Set<BlockPos> siblings, int rgb, float inset) {
        // Ободок кладём чуть дальше заливки — и тоже только вдоль нормали. Запас берём долей
        // от самого отступа: на дальних блоках постоянной добавки не хватило бы ровно по той
        // же причине, по которой не хватало постоянного отступа.
        float lift = inset * 1.6f + 0.001f;
        float x0 = pos.getX(), y0 = pos.getY(), z0 = pos.getZ();
        float x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;
        float b = RIM;

        switch (face) {
            case UP, DOWN -> {
                float y = face == Direction.UP ? y1 + lift : y0 - lift;
                if (open(siblings, pos, Direction.NORTH)) {
                    flat(pose, consumer, face, y, x0, x1, z0, z0 + b, red, green, blue);
                }
                if (open(siblings, pos, Direction.SOUTH)) {
                    flat(pose, consumer, face, y, x0, x1, z1 - b, z1, red, green, blue);
                }
                if (open(siblings, pos, Direction.WEST)) {
                    flat(pose, consumer, face, y, x0, x0 + b, z0, z1, red, green, blue);
                }
                if (open(siblings, pos, Direction.EAST)) {
                    flat(pose, consumer, face, y, x1 - b, x1, z0, z1, red, green, blue);
                }
            }
            case NORTH, SOUTH -> {
                float z = face == Direction.SOUTH ? z1 + lift : z0 - lift;
                if (open(siblings, pos, Direction.DOWN)) {
                    upright(pose, consumer, face, z, x0, x1, y0, y0 + b, red, green, blue);
                }
                if (open(siblings, pos, Direction.UP)) {
                    upright(pose, consumer, face, z, x0, x1, y1 - b, y1, red, green, blue);
                }
                if (open(siblings, pos, Direction.WEST)) {
                    upright(pose, consumer, face, z, x0, x0 + b, y0, y1, red, green, blue);
                }
                if (open(siblings, pos, Direction.EAST)) {
                    upright(pose, consumer, face, z, x1 - b, x1, y0, y1, red, green, blue);
                }
            }
            case WEST, EAST -> {
                float x = face == Direction.EAST ? x1 + lift : x0 - lift;
                if (open(siblings, pos, Direction.DOWN)) {
                    sideways(pose, consumer, face, x, y0, y0 + b, z0, z1, red, green, blue);
                }
                if (open(siblings, pos, Direction.UP)) {
                    sideways(pose, consumer, face, x, y1 - b, y1, z0, z1, red, green, blue);
                }
                if (open(siblings, pos, Direction.NORTH)) {
                    sideways(pose, consumer, face, x, y0, y1, z0, z0 + b, red, green, blue);
                }
                if (open(siblings, pos, Direction.SOUTH)) {
                    sideways(pose, consumer, face, x, y0, y1, z1 - b, z1, red, green, blue);
                }
            }
        }
    }

    /** Кончается ли размеченная область в эту сторону. */
    private static boolean open(Set<BlockPos> siblings, BlockPos pos, Direction direction) {
        return !siblings.contains(pos.relative(direction));
    }

    /** Полоска в горизонтальной плоскости (грани верх/низ). Обход тот же, что у самой грани. */
    private static void flat(PoseStack.Pose pose, VertexConsumer consumer, Direction face, float y,
                             float xa, float xb, float za, float zb, int red, int green, int blue) {
        if (face == Direction.UP) {
            quad(pose, consumer, xa, y, za, xa, y, zb, xb, y, zb, xb, y, za, red, green, blue, RIM_ALPHA);
        } else {
            quad(pose, consumer, xa, y, za, xb, y, za, xb, y, zb, xa, y, zb, red, green, blue, RIM_ALPHA);
        }
    }

    /** Полоска в вертикальной плоскости, обращённой на север или юг. */
    private static void upright(PoseStack.Pose pose, VertexConsumer consumer, Direction face, float z,
                                float xa, float xb, float ya, float yb, int red, int green, int blue) {
        if (face == Direction.SOUTH) {
            quad(pose, consumer, xa, ya, z, xb, ya, z, xb, yb, z, xa, yb, z, red, green, blue, RIM_ALPHA);
        } else {
            quad(pose, consumer, xa, ya, z, xa, yb, z, xb, yb, z, xb, ya, z, red, green, blue, RIM_ALPHA);
        }
    }

    /** Полоска в вертикальной плоскости, обращённой на запад или восток. */
    private static void sideways(PoseStack.Pose pose, VertexConsumer consumer, Direction face, float x,
                                 float ya, float yb, float za, float zb, int red, int green, int blue) {
        if (face == Direction.EAST) {
            quad(pose, consumer, x, ya, za, x, yb, za, x, yb, zb, x, ya, zb, red, green, blue, RIM_ALPHA);
        } else {
            quad(pose, consumer, x, ya, za, x, ya, zb, x, yb, zb, x, yb, za, red, green, blue, RIM_ALPHA);
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

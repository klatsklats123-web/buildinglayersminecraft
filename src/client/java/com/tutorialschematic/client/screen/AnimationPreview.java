package com.tutorialschematic.client.screen;

import com.tutorialschematic.order.Pos;
import com.tutorialschematic.schematic.BuildLayer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Панель предпросмотра: показывает, как слой собирается по текущим формулам.
 *
 * <p>Рисуется своей изометрией из обычных прямоугольников, а не через трёхмерный
 * рендер — так превью не зависит от состояния мира и работает в любом экране.
 * Блоки, до которых очередь ещё не дошла, показаны бледными призраками, только
 * что поставленные — вспышкой, поэтому фронт постройки видно сразу.
 */
public final class AnimationPreview {

    /** Больше этого числа блоков в превью не рисуем — прореживаем равномерно. */
    private static final int MAX_PREVIEW_BLOCKS = 12000;

    /** Сколько шагов подсвечивается как «только что поставленные». */
    private static final int FLASH_STEPS = 2;

    private int x, y, width, height;

    private BuildLayer layer;
    private List<Pos> ordered = List.of();
    private int[] stepOfBlock = new int[0];
    private int totalSteps;

    private double centerX, centerY, centerZ;
    private double modelRadius = 1;

    private float yaw = 35f;
    private float pitch = 30f;
    private float zoom = 1f;

    private long animationStart = System.currentTimeMillis();
    private boolean playing = true;
    private double speed = 1.0;

    /** Кэш проекции: пересчитывается только при повороте или смене данных. */
    private final List<Projected> projected = new ArrayList<>();
    private float cachedYaw = Float.NaN, cachedPitch = Float.NaN, cachedZoom = Float.NaN;
    private boolean dirty = true;

    private record Projected(float sx, float sy, float depth, int step, float shade) {
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.dirty = true;
    }

    /** Пересчитывает очередь для слоя. Вызывать после правки формул. */
    public void setLayer(BuildLayer layer) {
        this.layer = layer;
        refresh();
    }

    public void refresh() {
        if (layer == null || layer.isEmpty()) {
            ordered = List.of();
            stepOfBlock = new int[0];
            totalSteps = 0;
            dirty = true;
            return;
        }

        List<Pos> full = layer.orderedPositions();
        int batch = Math.max(1, layer.order().batchSize());
        totalSteps = (full.size() + batch - 1) / batch;

        // Прореживаем равномерно по очереди, а не по координатам: так порядок
        // постройки в превью остаётся честным, просто с меньшей детализацией.
        int stride = Math.max(1, full.size() / MAX_PREVIEW_BLOCKS);
        List<Pos> sampled = new ArrayList<>(full.size() / stride + 1);
        int[] steps = new int[full.size() / stride + 1];
        int written = 0;
        for (int i = 0; i < full.size(); i += stride) {
            if (written >= steps.length) {
                break;
            }
            sampled.add(full.get(i));
            steps[written++] = i / batch;
        }
        this.ordered = sampled;
        this.stepOfBlock = steps;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos pos : sampled) {
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }
        centerX = (minX + maxX) / 2.0;
        centerY = (minY + maxY) / 2.0;
        centerZ = (minZ + maxZ) / 2.0;
        modelRadius = Math.max(1, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) / 2.0 + 1);

        resetAnimation();
        dirty = true;
    }

    public void resetAnimation() {
        animationStart = System.currentTimeMillis();
    }

    public void togglePlaying() {
        playing = !playing;
        if (playing) {
            resetAnimation();
        }
    }

    public boolean playing() {
        return playing;
    }

    public double speed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = Math.max(0.1, Math.min(20, speed));
    }

    public void setAngles(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = Math.max(-89, Math.min(89, pitch));
        this.dirty = true;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public void rotate(double deltaYaw, double deltaPitch) {
        setAngles((float) (yaw + deltaYaw), (float) (pitch + deltaPitch));
    }

    public void zoom(double amount) {
        this.zoom = (float) Math.max(0.3, Math.min(4.0, zoom + amount * 0.15));
        this.dirty = true;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // ---- отрисовка ----

    public void render(GuiGraphicsExtractor graphics) {
        graphics.fill(x, y, x + width, y + height, 0xFF12141A);
        graphics.outline(x, y, width, height, 0xFF3A3F4B);

        if (ordered.isEmpty()) {
            return;
        }

        int currentStep = currentStep();
        updateProjection();

        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);

        int color = layer == null ? 0x88AAFF : layer.color();
        int size = blockSize();

        for (Projected block : projected) {
            boolean placed = block.step() <= currentStep;
            boolean fresh = placed && block.step() > currentStep - FLASH_STEPS;

            int fill;
            if (fresh) {
                fill = 0xFFFFFFFF;
            } else if (placed) {
                fill = 0xFF000000 | shade(color, block.shade());
            } else {
                // призрак: тускло, чтобы был виден силуэт, но не спорил с построенным
                fill = 0x33000000 | shade(color, block.shade() * 0.5f);
            }

            int px = (int) block.sx();
            int py = (int) block.sy();
            graphics.fill(px, py, px + size, py + size, fill);
            if (size >= 3 && placed) {
                // светлая верхняя грань добавляет объём почти бесплатно
                graphics.fill(px, py, px + size, py + 1, 0xFF000000 | shade(color, Math.min(1f, block.shade() * 1.6f)));
            }
        }

        graphics.disableScissor();
    }

    /** Номер шага анимации прямо сейчас. Дойдя до конца, превью держит паузу и начинает заново. */
    public int currentStep() {
        if (!playing || totalSteps <= 0) {
            return totalSteps;
        }
        int ticksPerStep = layer == null ? 2 : Math.max(1, layer.order().ticksPerStep());
        double secondsPerStep = ticksPerStep / 20.0 / speed;
        double elapsed = (System.currentTimeMillis() - animationStart) / 1000.0;

        double total = totalSteps * secondsPerStep;
        double pauseAtEnd = 1.2;
        if (elapsed > total + pauseAtEnd) {
            animationStart = System.currentTimeMillis();
            return 0;
        }
        return (int) Math.min(totalSteps, elapsed / secondsPerStep);
    }

    public int totalSteps() {
        return totalSteps;
    }

    /** Доля завершённости текущего прогона, 0..1 — для полоски прогресса. */
    public double progress() {
        return totalSteps <= 0 ? 0 : Math.min(1.0, (double) currentStep() / totalSteps);
    }

    private int blockSize() {
        double scale = Math.min(width, height) / (modelRadius * 2.6) * zoom;
        return Math.max(2, (int) Math.round(scale));
    }

    private void updateProjection() {
        if (!dirty && yaw == cachedYaw && pitch == cachedPitch && zoom == cachedZoom) {
            return;
        }
        cachedYaw = yaw;
        cachedPitch = pitch;
        cachedZoom = zoom;
        dirty = false;

        projected.clear();

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosYaw = Math.cos(yawRad), sinYaw = Math.sin(yawRad);
        double cosPitch = Math.cos(pitchRad), sinPitch = Math.sin(pitchRad);

        double scale = Math.min(width, height) / (modelRadius * 2.6) * zoom;
        double originX = x + width / 2.0;
        double originY = y + height / 2.0;

        for (int i = 0; i < ordered.size(); i++) {
            Pos pos = ordered.get(i);
            double dx = pos.x() - centerX;
            double dy = pos.y() - centerY;
            double dz = pos.z() - centerZ;

            // поворот вокруг вертикали, затем наклон камеры
            double rx = dx * cosYaw - dz * sinYaw;
            double rz = dx * sinYaw + dz * cosYaw;
            double ry = dy;

            double screenX = originX + rx * scale;
            double screenY = originY + (-ry * cosPitch + rz * sinPitch) * scale;
            double depth = rz * cosPitch + ry * sinPitch;

            // чем дальше блок, тем темнее — иначе облако точек читается как плоское
            float shade = (float) (0.55 + 0.45 * (depth / (modelRadius * 2) + 0.5));
            shade = Math.max(0.3f, Math.min(1.2f, shade));

            projected.add(new Projected((float) screenX, (float) screenY, (float) depth,
                    i < stepOfBlock.length ? stepOfBlock[i] : 0, shade));
        }

        // дальние рисуем первыми, ближние поверх
        projected.sort((a, b) -> Float.compare(a.depth(), b.depth()));
    }

    private static int shade(int rgb, float factor) {
        int red = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * factor));
        int green = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * factor));
        int blue = Math.min(255, Math.round((rgb & 0xFF) * factor));
        return (red << 16) | (green << 8) | blue;
    }
}

package com.tutorialschematic.lab;

import com.tutorialschematic.camera.CameraFraming;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.Occlusion;
import com.tutorialschematic.camera.ScenePlanner;
import com.tutorialschematic.camera.ShotPlanner;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.order.Pos;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Просмотр: что видит камера в этот тик, где она при этом стоит, и постановка своей.
 *
 * <p>Три вида, каждый отвечает на свой вопрос. «Кадр» — что попадёт в видео. «Со стороны» —
 * где камера физически находится: главная неразрешённая жалоба звучала как «снимает из
 * центра», и глазами это видно мгновенно, а числами ловилось долго. «Своя камера» — свободный
 * полёт, чтобы встать так, как надо, и сохранить ракурс.
 */
public final class ViewPanel extends JPanel {

    public enum Mode {
        /** Кадр камеры: ровно то, что попадёт в видео. */
        SHOT,
        /** Постройка со стороны, камеры видны как объекты. */
        FREE,
        /** Своя камера: свободный полёт. */
        FLY
    }

    private static final Color BACKGROUND = new Color(28, 30, 34);
    private static final Color BUILT = new Color(126, 134, 148);
    private static final Color PLACING = new Color(255, 186, 62);
    private static final Color PLACING_HIDDEN = new Color(226, 74, 74);
    private static final Color GHOST = new Color(58, 62, 70);
    private static final Color FRAME = new Color(236, 238, 242);
    private static final Color SAFE = new Color(104, 214, 138);
    private static final Color ALGO_MARK = new Color(96, 178, 255);
    private static final Color MINE_MARK = new Color(236, 130, 236);

    private LabSchematic schematic;
    private LabPipeline pipeline;
    private ShotStyle style;
    private MyShots myShots = new MyShots();
    /** Какую камеру показывать в виде «Кадр»: мою или алгоритма. */
    private boolean previewMine;
    private Mode mode = Mode.SHOT;
    private int tick;
    private double aspect = 9.0 / 16.0;
    private double fov = 70;
    private boolean showGhost = true;

    // облёт вида «со стороны»
    private double orbitYaw = 35, orbitPitch = 28, orbitZoom = 1.4;

    // своя камера: точка, углы и шаг полёта
    private double flyX, flyY, flyZ;
    private float flyYaw, flyPitch = 15;
    private boolean flyReady;
    private double flySpeed = 0.45;
    private final Set<Integer> pressed = new HashSet<>();
    private int dragX, dragY;

    public ViewPanel() {
        setBackground(BACKGROUND);
        setFocusable(true);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                // Фокус берём сам: иначе после щелчка по таймлайну WASD молча перестают работать,
                // и выглядит это как «управление не работает».
                if (mode == Mode.FLY) {
                    requestFocusInWindow();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                dragX = e.getX();
                dragY = e.getY();
                requestFocusInWindow();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - dragX;
                int dy = e.getY() - dragY;
                dragX = e.getX();
                dragY = e.getY();
                if (mode == Mode.FREE) {
                    orbitYaw -= dx * 0.5;
                    orbitPitch = Math.max(-85, Math.min(85, orbitPitch + dy * 0.5));
                    repaint();
                } else if (mode == Mode.FLY) {
                    flyYaw += dx * 0.35f;
                    flyPitch = (float) Math.max(-89, Math.min(89, flyPitch + dy * 0.35));
                    repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (mode == Mode.FREE) {
                    orbitZoom = Math.max(0.4, Math.min(6.0,
                            orbitZoom * (e.getWheelRotation() > 0 ? 1.12 : 0.89)));
                } else if (mode == Mode.FLY) {
                    if (e.isControlDown()) {
                        flySpeed = Math.max(0.05, Math.min(3.0,
                                flySpeed * (e.getWheelRotation() > 0 ? 0.85 : 1.18)));
                    } else {
                        // Подъезд вдоль луча взгляда: крупность меняется, наводка — нет.
                        dolly(-e.getWheelRotation() * Math.max(0.25, flySpeed * 2));
                    }
                }
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                pressed.add(e.getKeyCode());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                pressed.remove(e.getKeyCode());
            }
        });

        // Полёт крутится отдельным таймером: клавиатура даёт события рывками, а движение
        // должно быть ровным, иначе на глаз не понять, куда именно ты встал.
        new Timer(25, e -> flyStep()).start();
    }

    public void setData(LabSchematic schematic, LabPipeline pipeline, ShotStyle style, MyShots myShots) {
        this.schematic = schematic;
        this.pipeline = pipeline;
        this.style = style;
        this.myShots = myShots;
        this.flyReady = false;
        repaint();
    }

    public void setStyle(ShotStyle style) {
        this.style = style;
        repaint();
    }

    public void setTick(int tick) {
        this.tick = tick;
        repaint();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.FLY) {
            ensureFlyStart();
            requestFocusInWindow();
        }
        repaint();
    }

    public Mode mode() {
        return mode;
    }

    public void setPreviewMine(boolean previewMine) {
        this.previewMine = previewMine;
        repaint();
    }

    public void setAspect(double aspect) {
        this.aspect = aspect;
        repaint();
    }

    public void setFov(double fov) {
        this.fov = fov;
        repaint();
    }

    public void setShowGhost(boolean showGhost) {
        this.showGhost = showGhost;
        repaint();
    }

    public double aspect() {
        return aspect;
    }

    public double fov() {
        return fov;
    }

    /** Ставит свободную камеру в заданное положение — отсюда и начинается правка. */
    public void placeFlyAt(CameraShot from) {
        if (from != null) {
            flyX = from.x();
            flyY = from.y();
            flyZ = from.z();
            flyYaw = from.yaw();
            flyPitch = from.pitch();
            flyReady = true;
        }
        repaint();
    }

    /** Текущее положение свободной камеры — то, что станет ракурсом. */
    public CameraShot flyShot() {
        ensureFlyStart();
        return new CameraShot(tick, flyX, flyY, flyZ, flyYaw, flyPitch, true);
    }

    /** Двигает камеру вдоль луча взгляда: ближе или дальше, не сбивая наводку. */
    private void dolly(double blocks) {
        ensureFlyStart();
        double[] look = CameraFraming.direction(flyYaw, flyPitch);
        flyX += look[0] * blocks;
        flyY += look[1] * blocks;
        flyZ += look[2] * blocks;
    }

    /** Первое включение полёта: встаём туда же, где стоит алгоритм. */
    private void ensureFlyStart() {
        if (flyReady || schematic == null) {
            return;
        }
        CameraShot reference = null;
        if (pipeline != null && style != null) {
            LabPipeline.Track track = pipeline.track(style);
            reference = track == null ? null : track.shotAt(tick);
        }
        if (reference != null) {
            flyX = reference.x();
            flyY = reference.y();
            flyZ = reference.z();
            flyYaw = reference.yaw();
            flyPitch = reference.pitch();
        } else {
            double[] centre = ShotPlanner.centerOf(schematic.everything());
            double radius = ShotPlanner.radiusOf(schematic.everything(), centre);
            double[] position = CameraFraming.positionAround(centre, radius * 2.5, 45, 20);
            flyX = position[0];
            flyY = position[1];
            flyZ = position[2];
            float[] angles = CameraFraming.lookAt(position, centre);
            flyYaw = angles[0];
            flyPitch = angles[1];
        }
        flyReady = true;
    }

    private void flyStep() {
        if (mode != Mode.FLY || pressed.isEmpty() || !isShowing()) {
            return;
        }
        ensureFlyStart();
        double forward = 0, strafe = 0, lift = 0;
        if (pressed.contains(KeyEvent.VK_W)) forward += 1;
        if (pressed.contains(KeyEvent.VK_S)) forward -= 1;
        if (pressed.contains(KeyEvent.VK_D)) strafe += 1;
        if (pressed.contains(KeyEvent.VK_A)) strafe -= 1;
        if (pressed.contains(KeyEvent.VK_E) || pressed.contains(KeyEvent.VK_SPACE)) lift += 1;
        if (pressed.contains(KeyEvent.VK_Q) || pressed.contains(KeyEvent.VK_SHIFT)) lift -= 1;
        if (forward == 0 && strafe == 0 && lift == 0) {
            return;
        }
        double[] look = CameraFraming.direction(flyYaw, flyPitch);
        double[] flat = {look[0], 0, look[2]};
        double length = Math.hypot(flat[0], flat[2]);
        if (length > 1.0e-6) {
            flat[0] /= length;
            flat[2] /= length;
        }
        double[] right = {-flat[2], 0, flat[0]};

        flyX += (flat[0] * forward + right[0] * strafe) * flySpeed;
        flyZ += (flat[2] * forward + right[2] * strafe) * flySpeed;
        flyY += lift * flySpeed;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (schematic == null || pipeline == null || style == null) {
            g.setColor(FRAME);
            g.drawString("Загрузите схему: «Открыть схему…»", 20, 30);
            return;
        }
        LabPipeline.Track track = pipeline.track(style);
        CameraShot algorithmShot = track == null ? null : track.shotAt(tick);
        MyShots.Shot myActive = myShots.activeAt(tick);

        CameraShot viewShot;
        if (mode == Mode.FLY) {
            viewShot = flyShot();
        } else if (previewMine && myActive != null) {
            viewShot = myActive.toCamera();
        } else {
            viewShot = algorithmShot;
        }
        if (viewShot == null) {
            g.setColor(FRAME);
            g.drawString(previewMine ? "На этот момент своей камеры ещё нет"
                    : "На этот тик у дорожки нет кадра", 20, 30);
            return;
        }

        int globalStep = stepAt(tick);
        Set<Pos> standing = globalStep < pipeline.builtBeforeStep().size()
                ? pipeline.builtBeforeStep().get(globalStep) : Set.of();
        List<Pos> placingNow = globalStep < schematic.allSteps().size()
                ? schematic.allSteps().get(globalStep) : List.of();

        Scene3D.Viewport viewport = mode == Mode.FREE ? orbitViewport() : shotViewport(viewShot);

        List<Scene3D.Item> items = new ArrayList<>();
        if (showGhost) {
            Set<Pos> known = new HashSet<>(standing);
            known.addAll(placingNow);
            for (Pos pos : schematic.everything()) {
                if (!known.contains(pos)) {
                    items.add(new Scene3D.Item(pos, GHOST));
                }
            }
        }
        for (Pos pos : standing) {
            items.add(new Scene3D.Item(pos, BUILT));
        }
        double[] camera = {viewShot.x(), viewShot.y(), viewShot.z()};
        int hidden = 0;
        for (Pos pos : placingNow) {
            boolean visible = Occlusion.isVisible(camera, pos, standing);
            if (!visible) {
                hidden++;
            }
            items.add(new Scene3D.Item(pos, visible ? PLACING : PLACING_HIDDEN));
        }

        Scene3D.paint(g, viewport, items);

        if (mode == Mode.FREE) {
            paintBuildingBox(g, viewport);
            if (algorithmShot != null) {
                paintCameraMarker(g, viewport, algorithmShot, ALGO_MARK, "алгоритм");
            }
            if (myActive != null) {
                paintCameraMarker(g, viewport, myActive.toCamera(), MINE_MARK, "моя");
            }
        } else {
            paintFrameGuides(g, viewport);
        }
        paintStats(g, track, viewShot, placingNow, hidden);
    }

    /** Кадр камеры: letterbox под соотношение сторон итогового видео. */
    private Scene3D.Viewport shotViewport(CameraShot shot) {
        int w = getWidth(), h = getHeight();
        int frameW = w, frameH = (int) Math.round(w / aspect);
        if (frameH > h) {
            frameH = h;
            frameW = (int) Math.round(h * aspect);
        }
        double[] camera = {shot.x(), shot.y(), shot.z()};
        double[] direction = CameraFraming.direction(shot.yaw(), shot.pitch());
        double[] lookAt = {camera[0] + direction[0], camera[1] + direction[1], camera[2] + direction[2]};
        return new Scene3D.Viewport(camera, lookAt, fov, aspect,
                (w - frameW) / 2, (h - frameH) / 2, frameW, frameH);
    }

    private Scene3D.Viewport orbitViewport() {
        double[] centre = ShotPlanner.centerOf(schematic.everything());
        double radius = ShotPlanner.radiusOf(schematic.everything(), centre);
        double distance = radius * 3.0 * orbitZoom;
        double[] camera = CameraFraming.positionAround(centre, distance, orbitYaw, orbitPitch);
        double panelAspect = (double) getWidth() / Math.max(1, getHeight());
        return new Scene3D.Viewport(camera, centre, fov, panelAspect, 0, 0, getWidth(), getHeight());
    }

    private void paintFrameGuides(Graphics2D g, Scene3D.Viewport viewport) {
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(FRAME);
        int[] frame = viewport.boxOf(1.0);
        g.drawRect(frame[0], frame[1], frame[2], frame[3]);

        g.setColor(SAFE);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{6f, 6f}, 0f));
        int[] safe = viewport.boxOf(style.safeZone());
        g.drawRect(safe[0], safe[1], safe[2], safe[3]);
    }

    private void paintBuildingBox(Graphics2D g, Scene3D.Viewport viewport) {
        int[] box = MyShots.bounds(schematic.everything());
        double[][] corners = {
                {box[0], box[1], box[2]}, {box[3] + 1, box[1], box[2]},
                {box[3] + 1, box[1], box[5] + 1}, {box[0], box[1], box[5] + 1},
                {box[0], box[4] + 1, box[2]}, {box[3] + 1, box[4] + 1, box[2]},
                {box[3] + 1, box[4] + 1, box[5] + 1}, {box[0], box[4] + 1, box[5] + 1}
        };
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};

        g.setColor(new Color(120, 130, 150, 150));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{4f, 5f}, 0f));
        for (int[] edge : edges) {
            double[] a = viewport.toScreen(corners[edge[0]]);
            double[] b = viewport.toScreen(corners[edge[1]]);
            if (a != null && b != null) {
                g.drawLine((int) a[0], (int) a[1], (int) b[0], (int) b[1]);
            }
        }
    }

    private void paintCameraMarker(Graphics2D g, Scene3D.Viewport viewport, CameraShot shot,
                                   Color colour, String label) {
        double[] camera = {shot.x(), shot.y(), shot.z()};
        double[] screen = viewport.toScreen(camera);
        if (screen == null) {
            return;
        }
        double[] direction = CameraFraming.direction(shot.yaw(), shot.pitch());
        double[] ahead = {camera[0] + direction[0] * 6, camera[1] + direction[1] * 6,
                camera[2] + direction[2] * 6};
        double[] aheadScreen = viewport.toScreen(ahead);

        g.setStroke(new BasicStroke(2f));
        g.setColor(colour);
        if (aheadScreen != null) {
            g.drawLine((int) screen[0], (int) screen[1], (int) aheadScreen[0], (int) aheadScreen[1]);
        }
        g.fillOval((int) screen[0] - 7, (int) screen[1] - 7, 14, 14);

        boolean inside = MyShots.inside(shot, MyShots.bounds(schematic.everything()));
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.setColor(inside ? PLACING_HIDDEN : colour);
        g.drawString(label + (inside ? " — ВНУТРИ дома" : ""), (int) screen[0] + 12, (int) screen[1] - 8);
    }

    private void paintStats(Graphics2D g, LabPipeline.Track track, CameraShot viewShot,
                            List<Pos> placingNow, int hidden) {
        double[] camera = {viewShot.x(), viewShot.y(), viewShot.z()};
        double[] direction = CameraFraming.direction(viewShot.yaw(), viewShot.pitch());
        double[] lookAt = {camera[0] + direction[0], camera[1] + direction[1], camera[2] + direction[2]};

        int outOfFrame = 0, outOfSafe = 0;
        for (Pos pos : placingNow) {
            double[] point = {pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5};
            double[] screen = CameraFraming.project(camera, lookAt, point, fov, aspect);
            if (screen == null || Math.abs(screen[0]) > 1 || Math.abs(screen[1]) > 1) {
                outOfFrame++;
            } else if (Math.abs(screen[0]) > style.safeZone() || Math.abs(screen[1]) > style.safeZone()) {
                outOfSafe++;
            }
        }

        String sceneText = "—";
        int sceneIndex = track == null ? -1 : track.sceneAt(tick);
        if (track != null && sceneIndex >= 0 && sceneIndex < track.scenes().size()) {
            ScenePlanner.Scene scene = track.scenes().get(sceneIndex);
            sceneText = (sceneIndex + 1) + "/" + track.scenes().size()
                    + "  " + scene.shape() + "  блоков " + scene.blocks().size();
        }
        LabSchematic.Layer layer = MyShots.layerAt(schematic, tick);
        boolean inside = MyShots.inside(viewShot, MyShots.bounds(schematic.everything()));

        List<String> lines = new ArrayList<>();
        lines.add(switch (mode) {
            case SHOT -> (previewMine ? "МОЯ камера · " : "Алгоритм · ") + style.displayName();
            case FREE -> "Со стороны · " + style.displayName();
            case FLY -> "СВОЯ КАМЕРА — свободный полёт";
        });
        lines.add("тик " + tick + " / " + schematic.totalTicks()
                + (layer == null ? "" : "   слой: " + layer.name()));
        lines.add("сцена " + sceneText);
        lines.add("ставится блоков: " + placingNow.size()
                + "   заслонено: " + hidden
                + "   за кадром: " + outOfFrame
                + "   вне безопасной зоны: " + outOfSafe);
        lines.add("камера " + (inside ? "ВНУТРИ постройки" : "снаружи")
                + String.format("   поз %.1f %.1f %.1f   yaw %.0f  pitch %.0f",
                        camera[0], camera[1], camera[2], viewShot.yaw(), viewShot.pitch()));
        if (mode == Mode.FLY) {
            lines.add((hasFocus() ? "" : "⚠ щёлкните по картинке, чтобы заработали клавиши · ")
                    + "WASD — лететь, Q/E — вниз/вверх, мышь — оглядеться, "
                    + "колесо — ближе/дальше, Ctrl+колесо — шаг " + String.format("%.2f", flySpeed));
        } else if (mode == Mode.FREE) {
            lines.add("синяя точка — камера алгоритма, розовая — моя; мышь вращает, колесо приближает");
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int y = 18;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRect(6, y - 12, g.getFontMetrics().stringWidth(line) + 10, 17);
            boolean bad = (i == 3 && (hidden > 0 || outOfFrame > 0))
                    || (i == 4 && inside)
                    || (i == 5 && mode == Mode.FLY && !hasFocus());
            g.setColor(bad ? PLACING_HIDDEN : FRAME);
            g.drawString(line, 11, y);
            y += 18;
        }
    }

    /** Глобальный индекс шага, идущего на этот тик. */
    private int stepAt(int tick) {
        int[] ticks = schematic.stepTicks();
        int step = 0;
        for (int i = 0; i < ticks.length - 1; i++) {
            if (ticks[i] <= tick) {
                step = i;
            } else {
                break;
            }
        }
        return Math.min(step, Math.max(0, schematic.allSteps().size() - 1));
    }
}

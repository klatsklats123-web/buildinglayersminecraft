package com.tutorialschematic.lab;

import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.ScenePlanner;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * Таймлайн: что и когда строится, как это нарезано на сцены и где стоят камеры.
 *
 * <p>Камеры — точки: у ракурса есть момент, а не длительность, и щёлкать по точке привычнее,
 * чем по полосе. Точку можно выбрать (данные покажутся в панели справа), а свою — ещё и
 * перетащить по времени.
 *
 * <p>Слои идут встык. Между ними в записи есть паузы — мод специально останавливается после
 * слоя, — но рисовать их дырой неправильно: во время паузы текущим остаётся тот же слой,
 * просто в нём ничего не кладут. Дыры выглядели как потерянное время.
 */
public final class TimelinePanel extends JPanel {

    /** Что выбрано: ракурс алгоритма или мой. */
    public record Selection(boolean mine, int tick) {
    }

    private static final int LABEL_W = 118;
    private static final int PAD = 8;
    private static final int RIGHT_PAD = 30;

    private static final int LAYER_Y = 4, LAYER_H = 20;
    private static final int SCENE_Y = 28, SCENE_H = 16;
    private static final int ALGO_Y = 50, ALGO_H = 20;
    private static final int MINE_Y = 76, MINE_H = 20;
    private static final int RULER_Y = 104;
    private static final int HEIGHT = 126;
    /** Радиус попадания по точке мышью — с запасом, чтобы не приходилось целиться. */
    private static final int HIT = 9;

    private static final Color BACKGROUND = new Color(38, 41, 46);
    private static final Color TEXT = new Color(226, 230, 236);
    private static final Color DIM = new Color(150, 158, 170);
    private static final Color SCENE_A = new Color(66, 74, 86);
    private static final Color SCENE_B = new Color(88, 98, 114);
    private static final Color ALGO = new Color(96, 178, 255);
    private static final Color MINE = new Color(236, 130, 236);
    private static final Color SELECTED = new Color(255, 214, 92);
    private static final Color PLAYHEAD = new Color(255, 255, 255);

    private LabSchematic schematic;
    private LabPipeline.Track track;
    private MyShots myShots = new MyShots();
    private int tick;
    private Selection selection;

    private Consumer<Integer> onSeek = t -> { };
    private Consumer<Selection> onSelect = s -> { };
    private Runnable onShotsChanged = () -> { };
    private Integer draggingTick;

    public TimelinePanel() {
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(100, HEIGHT));
        setMinimumSize(new Dimension(100, HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (schematic == null) {
                    return;
                }
                Integer mine = myShotAt(e.getX(), e.getY());
                if (mine != null) {
                    select(new Selection(true, mine));
                    draggingTick = mine;
                    onSeek.accept(mine);
                    return;
                }
                Integer algo = algorithmShotAt(e.getX(), e.getY());
                if (algo != null) {
                    select(new Selection(false, algo));
                    onSeek.accept(algo);
                    return;
                }
                draggingTick = null;
                onSeek.accept(tickAt(e.getX()));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (schematic == null) {
                    return;
                }
                int target = tickAt(e.getX());
                if (draggingTick != null) {
                    if (target != draggingTick) {
                        myShots.move(draggingTick, target);
                        draggingTick = target;
                        select(new Selection(true, target));
                        onShotsChanged.run();
                    }
                }
                onSeek.accept(target);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                draggingTick = null;
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    private void select(Selection value) {
        selection = value;
        onSelect.accept(value);
        repaint();
    }

    public void setData(LabSchematic schematic, LabPipeline.Track track, MyShots myShots) {
        this.schematic = schematic;
        this.track = track;
        this.myShots = myShots;
        this.selection = null;
        repaint();
    }

    public void setTrack(LabPipeline.Track track, MyShots myShots) {
        this.track = track;
        this.myShots = myShots;
        this.selection = null;
        repaint();
    }

    public void setTick(int tick) {
        this.tick = tick;
        repaint();
    }

    public void setOnSeek(Consumer<Integer> onSeek) {
        this.onSeek = onSeek;
    }

    public void setOnSelect(Consumer<Selection> onSelect) {
        this.onSelect = onSelect;
    }

    public void setOnShotsChanged(Runnable listener) {
        this.onShotsChanged = listener;
    }

    public Selection selection() {
        return selection;
    }

    public void selectMine(int tick) {
        select(new Selection(true, tick));
    }

    public void selectAlgorithm(int tick) {
        select(new Selection(false, tick));
    }

    public void clearSelection() {
        selection = null;
        repaint();
    }

    private int total() {
        return schematic == null ? 1 : Math.max(1, schematic.totalTicks());
    }

    private int trackWidth() {
        return Math.max(1, getWidth() - LABEL_W - RIGHT_PAD);
    }

    private int xOf(int tick) {
        return LABEL_W + (int) Math.round((double) tick / total() * trackWidth());
    }

    private int tickAt(int x) {
        int value = (int) Math.round((double) (x - LABEL_W) / trackWidth() * total());
        return Math.max(0, Math.min(total(), value));
    }

    private Integer myShotAt(int x, int y) {
        if (y < MINE_Y - 4 || y > MINE_Y + MINE_H + 4) {
            return null;
        }
        Integer best = null;
        int bestDistance = HIT;
        for (MyShots.Shot shot : myShots.list()) {
            int distance = Math.abs(xOf(shot.tick()) - x);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = shot.tick();
            }
        }
        return best;
    }

    private Integer algorithmShotAt(int x, int y) {
        if (track == null || y < ALGO_Y - 4 || y > ALGO_Y + ALGO_H + 4) {
            return null;
        }
        Integer best = null;
        int bestDistance = HIT;
        for (CameraShot shot : track.shots()) {
            int distance = Math.abs(xOf(shot.tick()) - x);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = shot.tick();
            }
        }
        return best;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));

        if (schematic == null) {
            g.setColor(DIM);
            g.drawString("Схема не загружена", PAD, 20);
            return;
        }
        paintLayers(g);
        paintScenes(g);
        paintAlgorithmShots(g);
        paintMyShots(g);
        paintRuler(g);
        paintPlayhead(g);
    }

    private void rowLabel(Graphics2D g, String text, int baseline) {
        g.setColor(DIM);
        g.drawString(clip(g, text, LABEL_W - PAD * 2), PAD, baseline);
    }

    /** Слои встык: полоса тянется до начала следующего, пауза достаётся тому, кто её вызвал. */
    private void paintLayers(Graphics2D g) {
        List<LabSchematic.Layer> layers = schematic.layers();
        for (int i = 0; i < layers.size(); i++) {
            LabSchematic.Layer layer = layers.get(i);
            int x1 = xOf(layer.startTick());
            int until = i + 1 < layers.size() ? layers.get(i + 1).startTick() : total();
            int width = Math.max(1, xOf(until) - x1);

            g.setColor(new Color(layer.colour() | 0xFF000000, true));
            g.fillRect(x1, LAYER_Y, width, LAYER_H);
            g.setColor(new Color(0, 0, 0, 90));
            g.drawRect(x1, LAYER_Y, width, LAYER_H);
            if (width > 46) {
                g.setColor(Color.BLACK);
                g.drawString(clip(g, layer.name(), width - 6), x1 + 3, LAYER_Y + 14);
            }
        }
        rowLabel(g, "слои", LAYER_Y + 14);
    }

    private void paintScenes(Graphics2D g) {
        rowLabel(g, "сцены", SCENE_Y + 12);
        if (track == null || track.scenes().isEmpty()) {
            g.setColor(DIM);
            g.drawString("один кадр на всю запись", LABEL_W + 4, SCENE_Y + 12);
            return;
        }
        for (int i = 0; i < track.scenes().size(); i++) {
            ScenePlanner.Scene scene = track.scenes().get(i);
            int x1 = xOf(scene.startTick());
            int width = Math.max(1, xOf(scene.endTick()) - x1);
            g.setColor(i % 2 == 0 ? SCENE_A : SCENE_B);
            g.fillRect(x1, SCENE_Y, width, SCENE_H);
            g.setColor(new Color(0, 0, 0, 70));
            g.drawRect(x1, SCENE_Y, width, SCENE_H);
            if (width > 34) {
                g.setColor(TEXT);
                g.drawString(clip(g, String.valueOf(scene.shape()), width - 6), x1 + 3, SCENE_Y + 12);
            }
        }
    }

    private void paintAlgorithmShots(Graphics2D g) {
        rowLabel(g, "камеры алгоритма", ALGO_Y + 14);
        if (track == null) {
            return;
        }
        int centreY = ALGO_Y + ALGO_H / 2;
        g.setColor(new Color(255, 255, 255, 28));
        g.drawLine(LABEL_W, centreY, xOf(total()), centreY);

        for (CameraShot shot : track.shots()) {
            boolean chosen = selection != null && !selection.mine() && selection.tick() == shot.tick();
            paintDot(g, xOf(shot.tick()), centreY, chosen ? SELECTED : ALGO, chosen, shot.cut());
        }
    }

    private void paintMyShots(Graphics2D g) {
        rowLabel(g, "мои камеры", MINE_Y + 14);
        int centreY = MINE_Y + MINE_H / 2;
        g.setColor(new Color(255, 255, 255, 28));
        g.drawLine(LABEL_W, centreY, xOf(total()), centreY);

        if (myShots.isEmpty()) {
            g.setColor(DIM);
            g.drawString("пусто — нажмите «Создать ракурс»", LABEL_W + 6, MINE_Y + 14);
            return;
        }
        for (MyShots.Shot shot : myShots.list()) {
            boolean chosen = selection != null && selection.mine() && selection.tick() == shot.tick();
            paintDot(g, xOf(shot.tick()), centreY, chosen ? SELECTED : MINE, chosen, shot.cut());
        }
    }

    /** Точка камеры: залитая — рез, полая — плавный переход. Выбранная крупнее и в ободке. */
    private void paintDot(Graphics2D g, int x, int y, Color colour, boolean chosen, boolean cut) {
        int r = chosen ? 7 : 5;
        g.setColor(colour);
        if (cut) {
            g.fillOval(x - r, y - r, r * 2, r * 2);
        } else {
            g.setStroke(new BasicStroke(2f));
            g.drawOval(x - r, y - r, r * 2, r * 2);
        }
        if (chosen) {
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(x - r - 3, y - r - 3, (r + 3) * 2, (r + 3) * 2);
        }
    }

    private void paintRuler(Graphics2D g) {
        g.setColor(DIM);
        g.drawLine(LABEL_W, RULER_Y, xOf(total()), RULER_Y);
        int total = total();
        int stepTicks = Math.max(1, total / 10);
        for (int t = 0; t <= total; t += stepTicks) {
            int x = xOf(t);
            g.drawLine(x, RULER_Y, x, RULER_Y + 4);
            g.drawString(String.valueOf(t), x + 2, RULER_Y + 15);
        }
        rowLabel(g, "тики", RULER_Y + 15);
    }

    private void paintPlayhead(Graphics2D g) {
        int x = xOf(tick);
        g.setColor(PLAYHEAD);
        g.setStroke(new BasicStroke(1.4f));
        g.drawLine(x, LAYER_Y - 2, x, RULER_Y);
        g.fillPolygon(new int[]{x - 5, x + 5, x}, new int[]{LAYER_Y - 8, LAYER_Y - 8, LAYER_Y - 2}, 3);
    }

    private static String clip(Graphics2D g, String text, int maxWidth) {
        if (g.getFontMetrics().stringWidth(text) <= maxWidth) {
            return text;
        }
        String result = text;
        while (result.length() > 1 && g.getFontMetrics().stringWidth(result + "…") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }
}

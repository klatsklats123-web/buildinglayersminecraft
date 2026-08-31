package com.tutorialschematic.lab;

import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.ScenePlanner;
import com.tutorialschematic.camera.ShotPlanner;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

/**
 * Данные выбранной камеры — по одному полю в строке.
 *
 * <p>Сплошным текстом эти же цифры читались плохо: чтобы понять, чем мой ракурс отличается от
 * алгоритмического, глаз должен находить одноимённые строки друг под другом. Поэтому таблица,
 * а не абзац.
 */
public final class ShotDetails extends JScrollPane {

    private static final Color HEADING = new Color(120, 170, 230);
    private static final Color WARNING = new Color(210, 90, 90);
    private static final Color GOOD = new Color(40, 130, 70);

    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"поле", "значение"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);

    public ShotDetails() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(19);
        table.setShowGrid(false);
        table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                String field = String.valueOf(model.getValueAt(row, 0));
                String shown = String.valueOf(model.getValueAt(row, 1));
                // Заголовок раздела — пустое значение: выделяем его, чтобы разделы читались.
                boolean heading = shown.isEmpty();
                c.setFont(c.getFont().deriveFont(heading ? Font.BOLD : Font.PLAIN));
                if (selected) {
                    return c;
                }
                if (heading) {
                    c.setForeground(HEADING);
                } else if (field.equals("положение")) {
                    c.setForeground(shown.startsWith("ВНУТРИ") ? WARNING : GOOD);
                } else {
                    c.setForeground(Color.BLACK);
                }
                return c;
            }
        });
        setViewportView(table);
    }

    public void showNothing(String reason) {
        model.setRowCount(0);
        model.addRow(new Object[]{reason, ""});
    }

    /**
     * Заполняет таблицу по выбранной камере.
     *
     * @param mine       мой ли это ракурс (иначе — алгоритма)
     * @param shot       сам ракурс
     * @param other      ракурс другой стороны на этот же момент, либо {@code null}
     * @param note       заметка, если есть
     */
    public void show(LabSchematic schematic, LabPipeline.Track track, boolean mine,
                     CameraShot shot, CameraShot other, String note) {
        model.setRowCount(0);
        double[] centre = ShotPlanner.centerOf(schematic.everything());
        int[] box = MyShots.bounds(schematic.everything());

        row("ЧТО ЭТО", "");
        row("тип", mine ? "мой ракурс" : "ракурс алгоритма");
        row("тик", String.valueOf(shot.tick()));
        LabSchematic.Layer layer = MyShots.layerAt(schematic, shot.tick());
        row("слой", layer == null ? "—" : layer.name());
        int sceneIndex = track == null ? -1 : track.sceneAt(shot.tick());
        if (track != null && sceneIndex >= 0 && sceneIndex < track.scenes().size()) {
            ScenePlanner.Scene scene = track.scenes().get(sceneIndex);
            row("форма сцены", String.valueOf(scene.shape()));
            row("блоков в сцене", String.valueOf(scene.blocks().size()));
        }
        row("склейка", shot.cut() ? "рез" : "плавно");
        if (note != null && !note.isBlank()) {
            row("заметка", note);
        }

        row("", "");
        row("ГДЕ КАМЕРА", "");
        row("X", fmt(shot.x()));
        row("Y", fmt(shot.y()));
        row("Z", fmt(shot.z()));
        row("yaw", fmt(shot.yaw()) + "°");
        row("pitch", fmt(shot.pitch()) + "°");

        row("", "");
        row("ОТ ЦЕНТРА ДОМА", "");
        row("азимут", fmt(MyShots.azimuthOf(shot, centre)) + "°");
        row("подъём", fmt(MyShots.elevationOf(shot, centre)) + "°");
        row("дистанция", fmt(MyShots.distanceOf(shot, centre)));
        row("высота над низом", fmt(shot.y() - box[1]));
        row("положение", MyShots.inside(shot, box) ? "ВНУТРИ постройки" : "снаружи");

        if (other != null) {
            row("", "");
            row(mine ? "РАЗНИЦА С АЛГОРИТМОМ" : "РАЗНИЦА С МОЕЙ", "");
            double turn = ((MyShots.azimuthOf(shot, centre) - MyShots.azimuthOf(other, centre))
                    % 360 + 540) % 360 - 180;
            row("развёрнут на", fmt(turn) + "°");
            row("выше на", fmt(shot.y() - other.y()) + " бл.");
            row("дальше на", fmt(MyShots.distanceOf(shot, centre)
                    - MyShots.distanceOf(other, centre)) + " бл.");
            row("наклон", fmt(shot.pitch() - other.pitch()) + "°");
            row("сдвиг всего", fmt(Math.sqrt(
                    Math.pow(shot.x() - other.x(), 2)
                            + Math.pow(shot.y() - other.y(), 2)
                            + Math.pow(shot.z() - other.z(), 2))) + " бл.");
        }
    }

    private void row(String field, String value) {
        model.addRow(new Object[]{field, value});
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }
}

package com.tutorialschematic.lab;

import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.ShotStyle;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Лаборатория камер: смотреть, что снимет мод, и ставить свои ракурсы, чтобы показать, как надо.
 *
 * <p>Отдельная программа, а не часть мода, по одной причине: цикл «правка → сборка → запуск
 * игры → постройка → запись реплея → рендер видео → просмотр» занимал часы, и алгоритм из-за
 * этого правился вслепую, по косвенным числам. Здесь тот же самый код (подключён исходниками
 * из мода, см. build.gradle) отрабатывает за секунду.
 *
 * <p>Вторая половина смысла — своя камера. Объяснить словами, каким должен быть ракурс,
 * оказалось труднее, чем показать. Поставленные ракурсы ложатся в файл вместе с тем, что на
 * тот же момент выбрал алгоритм, и с посчитанной разницей — по ней и видна закономерность.
 */
public final class CameraLab extends JFrame {

    private static final String[] ASPECT_NAMES = {"Шортс 9:16", "Квадрат 1:1", "Горизонт 16:9"};
    private static final double[] ASPECT_VALUES = {9.0 / 16.0, 1.0, 16.0 / 9.0};

    private static final String[] SPEED_NAMES = {"0.1x", "0.25x", "0.5x", "1x", "2x", "4x", "8x"};
    private static final double[] SPEED_VALUES = {0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0};

    private final ViewPanel view = new ViewPanel();
    private final TimelinePanel timeline = new TimelinePanel();
    private final ShotDetails details = new ShotDetails();
    private final JComboBox<String> trackBox = new JComboBox<>();
    private final JComboBox<String> aspectBox = new JComboBox<>(ASPECT_NAMES);
    private final JComboBox<String> speedBox = new JComboBox<>(SPEED_NAMES);
    private final JComboBox<String> modeBox = new JComboBox<>(
            new String[]{"Кадр алгоритма", "Кадр моей камеры", "Со стороны", "Своя камера (полёт)"});
    private final JSpinner fovSpinner = new JSpinner(new SpinnerNumberModel(70, 30, 120, 5));
    private final JButton playButton = new JButton("▶ Играть");
    private final JLabel status = new JLabel("Схема не загружена");
    private final JTextField noteField = new JTextField();
    private final Timer playback;

    private LabSchematic schematic;
    private LabPipeline pipeline;
    private MyShots myShots = new MyShots();
    private List<ShotStyle> styles = List.of();
    private int tick;
    /** Дробный остаток тиков: на медленной скорости за одно срабатывание проходит меньше тика. */
    private double tickAccumulator;

    public CameraLab() {
        super("Лаборатория камер — Tutorial Schematic");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1460, 940);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());
        add(view, BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);
        add(buildSidePanel(), BorderLayout.EAST);

        speedBox.setSelectedIndex(3);
        timeline.setOnSeek(this::setTick);
        timeline.setOnSelect(selection -> showSelection());
        timeline.setOnShotsChanged(() -> {
            showSelection();
            view.repaint();
        });

        playback = new Timer(50, e -> advance());
    }

    private void setTick(int value) {
        int max = schematic == null ? 1 : Math.max(1, schematic.totalTicks());
        tick = Math.max(0, Math.min(max, value));
        view.setTick(tick);
        timeline.setTick(tick);
    }

    private void advance() {
        if (schematic == null) {
            return;
        }
        tickAccumulator += SPEED_VALUES[speedBox.getSelectedIndex()];
        int whole = (int) tickAccumulator;
        if (whole <= 0) {
            return;
        }
        tickAccumulator -= whole;
        int next = tick + whole;
        setTick(next > schematic.totalTicks() ? 0 : next);
    }

    private JPanel buildBottom() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));

        JButton open = new JButton("Открыть схему…");
        open.addActionListener(e -> chooseFile());
        row.add(open);
        row.add(Box.createHorizontalStrut(10));

        row.add(new JLabel("Дорожка:"));
        trackBox.addActionListener(e -> switchTrack());
        row.add(trackBox);
        row.add(Box.createHorizontalStrut(10));

        modeBox.addActionListener(e -> applyMode());
        row.add(new JLabel("Вид:"));
        row.add(modeBox);
        row.add(Box.createHorizontalStrut(10));

        row.add(new JLabel("Формат:"));
        aspectBox.addActionListener(e -> {
            view.setAspect(ASPECT_VALUES[aspectBox.getSelectedIndex()]);
            recompute();
        });
        row.add(aspectBox);
        row.add(Box.createHorizontalStrut(10));

        row.add(new JLabel("FOV:"));
        fovSpinner.addChangeListener(e -> {
            view.setFov(((Number) fovSpinner.getValue()).doubleValue());
            recompute();
        });
        row.add(fovSpinner);
        row.add(Box.createHorizontalStrut(10));

        JCheckBox ghost = new JCheckBox("Непостроенное", true);
        ghost.addActionListener(e -> view.setShowGhost(ghost.isSelected()));
        row.add(ghost);
        row.add(Box.createHorizontalStrut(10));

        JButton shapes = new JButton("Формы");
        shapes.addActionListener(e -> showText("Как разобрана постройка", shapesText()));
        row.add(shapes);

        JButton report = new JButton("Отчёт");
        report.addActionListener(e -> showText("Отчёт по числам", schematic == null ? ""
                : LabReport.build(schematic, pipeline, view.fov(), view.aspect())));
        row.add(report);
        row.add(Box.createHorizontalGlue());
        bottom.add(row);
        bottom.add(Box.createVerticalStrut(6));

        JPanel playRow = new JPanel();
        playRow.setLayout(new BoxLayout(playRow, BoxLayout.X_AXIS));
        playButton.addActionListener(e -> togglePlay());
        playRow.add(playButton);
        playRow.add(Box.createHorizontalStrut(8));
        playRow.add(new JLabel("Скорость:"));
        speedBox.setMaximumSize(new Dimension(80, 26));
        playRow.add(speedBox);
        playRow.add(Box.createHorizontalStrut(14));
        playRow.add(new JLabel("Точки на таймлайне — камеры. Щёлкните по точке, чтобы увидеть её данные."));
        playRow.add(Box.createHorizontalGlue());
        bottom.add(playRow);
        bottom.add(Box.createVerticalStrut(4));

        bottom.add(timeline);
        bottom.add(Box.createVerticalStrut(4));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        bottom.add(status);
        return bottom;
    }

    private JPanel buildSidePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.setPreferredSize(new Dimension(340, 100));

        JButton create = new JButton("➕ Создать ракурс здесь");
        create.setFont(create.getFont().deriveFont(Font.BOLD, 13f));
        create.setAlignmentX(LEFT_ALIGNMENT);
        create.addActionListener(e -> createShot());
        panel.add(create);
        panel.add(Box.createVerticalStrut(4));

        JLabel createHint = new JLabel("<html><small>Переходит в свободный полёт и ставит камеру "
                + "на текущем тике. Летите куда надо и жмите «Сохранить положение».</small></html>");
        panel.add(createHint);
        panel.add(Box.createVerticalStrut(8));

        JButton save = new JButton("Сохранить положение в выбранный");
        save.setAlignmentX(LEFT_ALIGNMENT);
        save.addActionListener(e -> saveFlyIntoSelected());
        panel.add(save);

        JButton edit = new JButton("Править выбранный (полёт отсюда)");
        edit.setAlignmentX(LEFT_ALIGNMENT);
        edit.addActionListener(e -> editSelected());
        panel.add(edit);

        JButton delete = new JButton("Удалить выбранный");
        delete.setAlignmentX(LEFT_ALIGNMENT);
        delete.addActionListener(e -> deleteSelected());
        panel.add(delete);
        panel.add(Box.createVerticalStrut(8));

        panel.add(new JLabel("Заметка к выбранному ракурсу:"));
        noteField.setMaximumSize(new Dimension(320, 26));
        noteField.addActionListener(e -> applyNote());
        panel.add(noteField);
        JButton applyNote = new JButton("Записать заметку");
        applyNote.setAlignmentX(LEFT_ALIGNMENT);
        applyNote.addActionListener(e -> applyNote());
        panel.add(applyNote);
        panel.add(Box.createVerticalStrut(8));

        details.setPreferredSize(new Dimension(320, 420));
        panel.add(details);
        panel.add(Box.createVerticalStrut(8));

        JButton write = new JButton("Записать всё в файл");
        write.setAlignmentX(LEFT_ALIGNMENT);
        write.addActionListener(e -> saveToFile());
        panel.add(write);

        JButton read = new JButton("Прочитать из файла");
        read.setAlignmentX(LEFT_ALIGNMENT);
        read.addActionListener(e -> loadFromFile());
        panel.add(read);
        panel.add(Box.createVerticalGlue());

        details.showNothing("Схема не загружена");
        return panel;
    }

    private void applyMode() {
        switch (modeBox.getSelectedIndex()) {
            case 1 -> {
                view.setPreviewMine(true);
                view.setMode(ViewPanel.Mode.SHOT);
            }
            case 2 -> {
                view.setPreviewMine(false);
                view.setMode(ViewPanel.Mode.FREE);
            }
            case 3 -> {
                view.setPreviewMine(false);
                view.setMode(ViewPanel.Mode.FLY);
            }
            default -> {
                view.setPreviewMine(false);
                view.setMode(ViewPanel.Mode.SHOT);
            }
        }
    }

    /** Создаёт свой ракурс на текущем тике и сразу переводит в полёт, чтобы его поставить. */
    private void createShot() {
        if (schematic == null) {
            return;
        }
        LabPipeline.Track track = currentTrack();
        CameraShot from = track == null ? null : track.shotAt(tick);
        if (from != null) {
            view.placeFlyAt(from);
        }
        modeBox.setSelectedIndex(3);
        applyMode();

        CameraShot fly = view.flyShot();
        myShots.put(new MyShots.Shot(tick, fly.x(), fly.y(), fly.z(), fly.yaw(), fly.pitch(),
                true, noteField.getText().trim()));
        timeline.selectMine(tick);
        timeline.repaint();
        showSelection();
        status.setText("Ракурс создан на тике " + tick
                + ". Летите куда надо и нажмите «Сохранить положение в выбранный». Всего своих: "
                + myShots.size());
    }

    /** Записывает текущее положение свободной камеры в выбранный свой ракурс. */
    private void saveFlyIntoSelected() {
        TimelinePanel.Selection selection = timeline.selection();
        if (selection == null || !selection.mine()) {
            status.setText("Сначала выберите свой ракурс (розовая точка) или создайте новый");
            return;
        }
        MyShots.Shot existing = myShots.get(selection.tick());
        if (existing == null) {
            return;
        }
        CameraShot fly = view.flyShot();
        myShots.put(new MyShots.Shot(existing.tick(), fly.x(), fly.y(), fly.z(),
                fly.yaw(), fly.pitch(), existing.cut(),
                noteField.getText().isBlank() ? existing.note() : noteField.getText().trim()));
        timeline.repaint();
        view.repaint();
        showSelection();
        status.setText("Положение записано в ракурс на тике " + existing.tick());
    }

    private void editSelected() {
        TimelinePanel.Selection selection = timeline.selection();
        if (selection == null) {
            status.setText("Сначала выберите камеру на таймлайне");
            return;
        }
        CameraShot from = selection.mine()
                ? (myShots.get(selection.tick()) == null ? null : myShots.get(selection.tick()).toCamera())
                : (currentTrack() == null ? null : currentTrack().shotAt(selection.tick()));
        setTick(selection.tick());
        view.placeFlyAt(from);
        modeBox.setSelectedIndex(3);
        applyMode();
        status.setText("Полёт от выбранной камеры. Потом — «Сохранить положение в выбранный»");
    }

    private void deleteSelected() {
        TimelinePanel.Selection selection = timeline.selection();
        if (selection == null || !selection.mine()) {
            status.setText("Удалять можно только свои ракурсы (розовые точки)");
            return;
        }
        myShots.remove(selection.tick());
        timeline.clearSelection();
        timeline.repaint();
        view.repaint();
        showSelection();
        status.setText("Ракурс удалён. Осталось своих: " + myShots.size());
    }

    private void applyNote() {
        TimelinePanel.Selection selection = timeline.selection();
        if (selection == null || !selection.mine()) {
            status.setText("Заметка пишется к своему ракурсу — выберите розовую точку");
            return;
        }
        MyShots.Shot shot = myShots.get(selection.tick());
        if (shot != null) {
            myShots.put(shot.withNote(noteField.getText().trim()));
            showSelection();
            status.setText("Заметка записана");
        }
    }

    /** Показывает данные выбранной камеры и ту, что стоит на этот же момент с другой стороны. */
    private void showSelection() {
        if (schematic == null) {
            details.showNothing("Схема не загружена");
            return;
        }
        TimelinePanel.Selection selection = timeline.selection();
        if (selection == null) {
            details.showNothing("Камера не выбрана — щёлкните по точке на таймлайне");
            noteField.setText("");
            return;
        }
        LabPipeline.Track track = currentTrack();
        if (selection.mine()) {
            MyShots.Shot shot = myShots.get(selection.tick());
            if (shot == null) {
                details.showNothing("Этого ракурса больше нет");
                return;
            }
            CameraShot algorithm = track == null ? null : track.shotAt(shot.tick());
            details.show(schematic, track, true, shot.toCamera(), algorithm, shot.note());
            noteField.setText(shot.note() == null ? "" : shot.note());
        } else {
            if (track == null) {
                details.showNothing("У этой дорожки нет кадров");
                return;
            }
            CameraShot algorithm = null;
            for (CameraShot candidate : track.shots()) {
                if (candidate.tick() == selection.tick()) {
                    algorithm = candidate;
                }
            }
            if (algorithm == null) {
                details.showNothing("Кадр не найден");
                return;
            }
            MyShots.Shot mine = myShots.activeAt(selection.tick());
            details.show(schematic, track, false, algorithm,
                    mine == null ? null : mine.toCamera(), null);
            noteField.setText("");
        }
    }

    private LabPipeline.Track currentTrack() {
        ShotStyle style = currentStyle();
        return style == null || pipeline == null ? null : pipeline.track(style);
    }

    private ShotStyle currentStyle() {
        int index = trackBox.getSelectedIndex();
        return index >= 0 && index < styles.size() ? styles.get(index)
                : (styles.isEmpty() ? null : styles.get(0));
    }

    private void saveToFile() {
        ShotStyle style = currentStyle();
        if (schematic == null || style == null) {
            return;
        }
        if (myShots.isEmpty()) {
            status.setText("Пока нечего записывать — ни одного своего ракурса");
            return;
        }
        try {
            Path path = MyShots.fileFor(schematic, style);
            myShots.save(path, schematic, style, currentTrack());
            status.setText("Записано ракурсов: " + myShots.size() + " → " + path);
            JOptionPane.showMessageDialog(this,
                    "Ракурсы сохранены:\n" + path + "\n\nВ файле рядом с каждым лежит то, что на "
                            + "тот же момент\nвыбрал алгоритм, и посчитанная разница между ними.",
                    "Сохранено", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Не удалось сохранить:\n" + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadFromFile() {
        ShotStyle style = currentStyle();
        if (schematic == null || style == null) {
            return;
        }
        try {
            Path path = MyShots.fileFor(schematic, style);
            myShots = MyShots.load(path);
            view.setData(schematic, pipeline, style, myShots);
            timeline.setTrack(pipeline.track(style), myShots);
            showSelection();
            status.setText("Прочитано ракурсов: " + myShots.size() + " из " + path);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Не удалось прочитать:\n" + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void togglePlay() {
        if (playback.isRunning()) {
            playback.stop();
            playButton.setText("▶ Играть");
        } else {
            playback.start();
            playButton.setText("⏸ Пауза");
        }
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Схемы (.ltutorial)", "ltutorial"));
        Path guess = defaultFolder();
        if (guess != null) {
            chooser.setCurrentDirectory(guess.toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            load(chooser.getSelectedFile().toPath());
        }
    }

    /** Где мод хранит схемы — одна папка на все миры. */
    private static Path defaultFolder() {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return null;
        }
        Path modrinth = Path.of(appData, "ModrinthApp", "profiles");
        if (!Files.isDirectory(modrinth)) {
            return null;
        }
        try (var profiles = Files.list(modrinth)) {
            for (Path profile : profiles.toList()) {
                Path schematics = profile.resolve("tutorial-schematics");
                if (Files.isDirectory(schematics)) {
                    return schematics;
                }
            }
        } catch (Exception ignored) {
            // не нашли — откроется обычный диалог
        }
        return null;
    }

    public void load(Path path) {
        try {
            schematic = LabSchematic.load(path);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Не удалось прочитать схему:\n" + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        recompute();
        setTick(0);
        status.setText("Схема «" + schematic.name() + "»: "
                + schematic.everything().size() + " блоков, "
                + schematic.layers().size() + " слоёв, "
                + schematic.allSteps().size() + " шагов, "
                + schematic.totalTicks() + " тиков"
                + (myShots.isEmpty() ? "" : ";  своих ракурсов: " + myShots.size()));
    }

    /** У каждой дорожки свои ракурсы: сравнивать их между собой нечего. */
    private void switchTrack() {
        ShotStyle style = currentStyle();
        if (style == null || pipeline == null || schematic == null) {
            return;
        }
        myShots = loadQuietly(style);
        view.setStyle(style);
        view.setData(schematic, pipeline, style, myShots);
        timeline.setTrack(pipeline.track(style), myShots);
        showSelection();
    }

    private MyShots loadQuietly(ShotStyle style) {
        try {
            return MyShots.load(MyShots.fileFor(schematic, style));
        } catch (Exception e) {
            return new MyShots();
        }
    }

    private void recompute() {
        if (schematic == null) {
            return;
        }
        double fov = ((Number) fovSpinner.getValue()).doubleValue();
        double aspect = ASPECT_VALUES[aspectBox.getSelectedIndex()];
        pipeline = new LabPipeline(schematic, fov, aspect);
        styles = pipeline.exportedStyles();

        int keep = trackBox.getSelectedIndex();
        trackBox.removeAllItems();
        for (ShotStyle style : styles) {
            trackBox.addItem(style.displayName());
        }
        if (keep >= 0 && keep < styles.size()) {
            trackBox.setSelectedIndex(keep);
        } else if (!styles.isEmpty()) {
            trackBox.setSelectedIndex(0);
        }
        ShotStyle style = currentStyle();
        myShots = style == null ? new MyShots() : loadQuietly(style);

        view.setAspect(aspect);
        view.setFov(fov);
        view.setData(schematic, pipeline, style, myShots);
        timeline.setData(schematic, style == null ? null : pipeline.track(style), myShots);
        showSelection();
    }

    /** Что увидел анализатор: какие куски работы и какой формы он нашёл в каждом слое. */
    private String shapesText() {
        if (schematic == null || pipeline == null) {
            return "Схема не загружена";
        }
        StringBuilder out = new StringBuilder();
        out.append("Слои схемы и то, как их разобрал анализатор.\n")
                .append("Форма решает, как слой снимать: плоскость (пол, крыша) — одно,\n")
                .append("вертикаль (столб) — другое, вытянутое (стена) — третье.\n")
                .append("BLOB значит «ни на что не похоже» — такие места и снимаются хуже всего.\n\n");

        for (LabSchematic.Layer layer : schematic.layers()) {
            out.append(String.format("%-28s блоков %4d, шагов %4d, тики %d..%d%n",
                    layer.name(), layer.blocks().size(), layer.steps().size(),
                    layer.startTick(), layer.endTick()));
        }
        out.append("\nКуски работы (общие для всех дорожек):\n");
        for (var segment : pipeline.segments()) {
            LabSchematic.Layer layer = MyShots.layerAt(schematic, segment.startTick());
            out.append(String.format("  %-10s блоков %4d  направление %6s  тики %5d..%-5d  %s%n",
                    segment.shape(), segment.blocks().size(),
                    Double.isNaN(segment.direction()) ? "нет"
                            : String.format("%.0f", segment.direction()),
                    segment.startTick(), segment.endTick(),
                    layer == null ? "" : layer.name()));
        }
        return out.toString();
    }

    private void showText(String title, String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(820, 520));
        JOptionPane.showMessageDialog(this, scroll, title, JOptionPane.PLAIN_MESSAGE);
    }

    public static void main(String[] args) {
        if (List.of(args).contains("--report")) {
            Path path = null;
            for (String arg : args) {
                if (!arg.startsWith("--")) {
                    path = Path.of(arg);
                }
            }
            if (path == null) {
                System.err.println("Укажите путь к .ltutorial");
                return;
            }
            try {
                LabSchematic loaded = LabSchematic.load(path);
                double aspect = ASPECT_VALUES[0];
                LabPipeline computed = new LabPipeline(loaded, 70, aspect);
                System.out.println(LabReport.build(loaded, computed, 70, aspect));
            } catch (Exception e) {
                System.err.println("Не удалось: " + e);
                e.printStackTrace();
            }
            return;
        }

        SwingUtilities.invokeLater(() -> {
            CameraLab lab = new CameraLab();
            lab.setVisible(true);
            if (args.length > 0) {
                lab.load(Path.of(args[0]));
            } else {
                Path folder = defaultFolder();
                if (folder != null) {
                    try (var files = Files.list(folder)) {
                        File first = files.filter(p -> p.toString().endsWith(".ltutorial"))
                                .findFirst().map(Path::toFile).orElse(null);
                        if (first != null) {
                            lab.load(first.toPath());
                        }
                    } catch (Exception ignored) {
                        // просто откроется пустым
                    }
                }
            }
        });
    }
}

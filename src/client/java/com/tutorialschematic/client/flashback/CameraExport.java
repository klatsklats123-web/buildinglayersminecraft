package com.tutorialschematic.client.flashback;

import com.tutorialschematic.camera.BuildTimeline;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.ShotPlanner;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.order.Pos;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Расстановка камер по готовой записи.
 *
 * <p>Собирает вместе три вещи: схему (какие блоки в каком слое), метки из реплея (на каком
 * тике начался каждый слой) и геометрию ({@link ShotPlanner} — откуда слой лучше видно).
 *
 * <p>Метки тут не для красоты: свой счётчик тиков Flashback наружу не отдаёт, поэтому
 * единственный способ узнать, когда именно начался слой, — прочитать метку, которую мы
 * сами же поставили во время записи.
 */
public final class CameraExport {

    /** Подпись метки. Номер слоя потом вычитывается отсюда же. */
    public static final String MARKER_PREFIX = "Слой ";
    /** Метка конца постройки — даёт последнему слою момент окончания для пролётов. */
    public static final String MARKER_END = "Постройка завершена";


    private CameraExport() {
    }

    /**
     * Метка на старте слоя: подпись с номером и именем, цвет — цвет самого слоя.
     *
     * <p>Уходит на клиентский поток: постройка идёт на серверном, а запись Flashback
     * клиентская, и трогать её оттуда напрямую нельзя.
     */
    public static void markLayerStart(int index, BuildLayer layer) {
        String description = MARKER_PREFIX + (index + 1) + ": " + layer.name();
        int colour = layer.color();
        Minecraft.getInstance().execute(() -> FlashbackBridge.addMarker(colour, description));
    }

    public static void markBuildFinished() {
        Minecraft.getInstance().execute(() -> FlashbackBridge.addMarker(0xFFFFFF, MARKER_END));
    }

    /**
     * Читает свежий реплей и пишет к нему дорожки камер.
     *
     * @return путь к файлу камер либо {@code null}, если не вышло — причина уходит в чат
     */
    @Nullable
    public static Path exportForNewestReplay() {
        TutorialSchematic schematic = EditorState.get().schematic();
        if (schematic == null) {
            EditorState.error("Нет открытой схемы");
            return null;
        }
        if (!FlashbackBridge.isAvailable()) {
            EditorState.error("Flashback не найден — расставлять камеры некуда");
            return null;
        }
        if (FlashbackBridge.isRecording()) {
            EditorState.error("Сначала остановите запись: пока она идёт, реплей ещё не дописан");
            return null;
        }

        Path replay = ReplayMetadata.newestReplay();
        if (replay == null) {
            EditorState.error("В папке Flashback нет ни одного реплея");
            return null;
        }
        ReplayMetadata meta = ReplayMetadata.read(replay);
        if (meta == null) {
            EditorState.error("Не удалось прочитать метаданные реплея " + replay.getFileName());
            return null;
        }

        List<LayerTiming> timings = matchLayers(schematic, meta);
        if (timings.isEmpty()) {
            EditorState.error("В реплее нет наших меток — записывали не эту постройку "
                    + "или запись шла без мода");
            return null;
        }

        Map<ShotStyle, List<CameraShot>> tracks = planTracks(schematic, timings);
        Path written = EditorStateWriter.write(meta.uuid(), tracks);
        if (written == null) {
            EditorState.error("Камеры не записаны — состояние редактора для этого реплея уже есть");
            return null;
        }

        EditorState.info("Камеры расставлены: " + timings.size() + " слоёв, "
                + tracks.size() + " дорожек. Реплей: " + replay.getFileName());
        return written;
    }

    /** Слой вместе с тем, когда он начался и когда кончился, в тиках записи. */
    private record LayerTiming(BuildLayer layer, int index, int startTick, int endTick) {
    }

    /**
     * Сопоставляет метки из реплея со слоями схемы. Номер слоя берётся из подписи метки,
     * поэтому переставленные или переименованные слои не собьют привязку.
     */
    private static List<LayerTiming> matchLayers(TutorialSchematic schematic, ReplayMetadata meta) {
        List<int[]> found = new ArrayList<>();
        int endTick = -1;

        for (Map.Entry<Integer, String> entry : meta.markers().entrySet()) {
            String description = entry.getValue();
            if (MARKER_END.equals(description)) {
                endTick = entry.getKey();
                continue;
            }
            if (!description.startsWith(MARKER_PREFIX)) {
                continue;
            }
            int colon = description.indexOf(':');
            if (colon < 0) {
                continue;
            }
            try {
                int number = Integer.parseInt(description.substring(MARKER_PREFIX.length(), colon).trim());
                found.add(new int[]{entry.getKey(), number - 1});
            } catch (NumberFormatException ignored) {
                // чужая метка с похожей подписью — просто пропускаем
            }
        }

        List<LayerTiming> result = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            int startTick = found.get(i)[0];
            int layerIndex = found.get(i)[1];
            BuildLayer layer = schematic.layerAt(layerIndex);
            if (layer == null || layer.isEmpty()) {
                continue;
            }
            int finish = i + 1 < found.size() ? found.get(i + 1)[0] : endTick;
            if (finish <= startTick) {
                // последний слой без метки конца — даём ему условные десять секунд
                finish = startTick + 200;
            }
            result.add(new LayerTiming(layer, layerIndex, startTick, finish));
        }
        return result;
    }

    private static Map<ShotStyle, List<CameraShot>> planTracks(TutorialSchematic schematic,
                                                               List<LayerTiming> timings) {
        double fov = Minecraft.getInstance().options.fov().get();
        Map<ShotStyle, List<CameraShot>> tracks = new EnumMap<>(ShotStyle.class);
        Map<ShotStyle, Double> lastAzimuth = new EnumMap<>(ShotStyle.class);
        for (ShotStyle style : ShotStyle.values()) {
            tracks.put(style, new ArrayList<>());
            lastAzimuth.put(style, Double.NaN);
        }

        // заслоняют только те слои, что уже построены к этому моменту — копим по ходу
        Set<Pos> built = new HashSet<>();

        // Общий план ставится один раз на всю запись и кадрируется по всей постройке
        // целиком — к нему возвращаются, чтобы увидеть, насколько дом вырос.
        List<Pos> everything = new ArrayList<>();
        for (LayerTiming timing : timings) {
            everything.addAll(positionsOf(timing.layer()));
        }
        for (ShotStyle style : ShotStyle.values()) {
            if (style.wholeBuild() && !everything.isEmpty()) {
                tracks.get(style).add(ShotPlanner.plan(everything, Set.of(), style, fov,
                        timings.get(0).startTick(), Double.NaN).shot());
            }
        }

        for (LayerTiming timing : timings) {
            List<Pos> targets = positionsOf(timing.layer());
            // Где идёт работа в каждый момент слоя — по этому камера её и ведёт.
            List<BuildTimeline.FrontSample> front = BuildTimeline.sample(
                    timing.layer().steps(), timing.startTick(), timing.endTick() - 1);
            // А это моменты, в которые проверяется видимость. Блоки, поставленные раньше
            // внутри того же слоя, заслоняют фронт не хуже соседних слоёв, поэтому они
            // копятся по ходу — иначе камера встаёт там, откуда к концу слоя ничего не видно.
            List<ShotPlanner.VisibilityCheck> checks = visibilityChecks(timing.layer().steps(), built);

            for (ShotStyle style : ShotStyle.values()) {
                if (style.wholeBuild()) {
                    continue;
                }
                ShotPlanner.Placement start = ShotPlanner.plan(targets, checks, style, fov,
                        timing.startTick(), lastAzimuth.get(style));
                lastAzimuth.put(style, start.azimuth());

                // Конечный кадр ставим на тик раньше следующего слоя: кадры лежат
                // в словаре по тику, и совпадение просто затёрло бы соседний.
                tracks.get(style).addAll(ShotPlanner.followShots(
                        targets, start, style, front, timing.endTick() - 1));
            }
            built.addAll(targets);
        }
        return tracks;
    }

    /**
     * Несколько моментов слоя для проверки видимости: начало, середина и конец.
     *
     * <p>К каждому моменту заслонами считаются и предыдущие слои, и то, что успели
     * поставить в этом же слое. Без второго камера норовит встать там, откуда начало
     * слоя видно прекрасно, а конец не видно вовсе.
     */
    private static List<ShotPlanner.VisibilityCheck> visibilityChecks(List<List<Pos>> steps,
                                                                      Set<Pos> alreadyBuilt) {
        List<ShotPlanner.VisibilityCheck> checks = new ArrayList<>();
        if (steps.isEmpty()) {
            return checks;
        }
        Set<Pos> occluders = new HashSet<>(alreadyBuilt);
        int slices = Math.min(3, steps.size());

        for (int i = 0; i < slices; i++) {
            int from = (int) ((long) i * steps.size() / slices);
            int to = (int) ((long) (i + 1) * steps.size() / slices);

            List<Pos> window = new ArrayList<>();
            for (int step = from; step < to; step++) {
                window.addAll(steps.get(step));
            }
            if (!window.isEmpty()) {
                checks.add(new ShotPlanner.VisibilityCheck(window, new HashSet<>(occluders)));
                occluders.addAll(window);
            }
        }
        return checks;
    }

    private static List<Pos> positionsOf(BuildLayer layer) {
        List<Pos> result = new ArrayList<>(layer.blockCount());
        for (BlockPos pos : layer.blocks().keySet()) {
            result.add(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        }
        return result;
    }
}

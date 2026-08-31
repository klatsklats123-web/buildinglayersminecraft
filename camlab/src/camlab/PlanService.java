package camlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tutorialschematic.camera.BuildAnalyzer;
import com.tutorialschematic.camera.BuildEnvelope;
import com.tutorialschematic.camera.BuildTimeline;
import com.tutorialschematic.camera.CameraFraming;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.Occlusion;
import com.tutorialschematic.camera.SafetyValidator;
import com.tutorialschematic.camera.ScenePlanner;
import com.tutorialschematic.camera.ShotPlanner;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.order.BlockOrderer;
import com.tutorialschematic.order.OrderConfig;
import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Считает полный план для просмотрщика: раскадровку анимации, дорожки камер и метрики.
 *
 * <p>Оркестрация нарочно повторяет боевой {@code CameraExport.planTracks} — лаборатория
 * существует, чтобы смотреть на настоящие алгоритмы, а не на их пересказ. Отличий два, оба
 * неустранимые вне игры: тики слоёв синтезируются из настроек анимации (в игре они берутся
 * из меток реплея), и нет сканирования мира на посторонние заслоны (дерево, рельеф).
 *
 * <p>Метрики — те же, которыми проверялась переделка на огибающую (см. CAMERA_PROBLEM.md
 * §8): для каждого блока в момент его установки — виден ли он и попадает ли в кадр, плюс
 * доля кадров внутри габарита дома.
 */
public final class PlanService {

    private PlanService() {
    }

    public static JsonObject plan(LoadedSchematic schematic, JsonObject settings) {
        double fov = get(settings, "fov", 70.0);
        double aspect = get(settings, "aspect", 9.0 / 16.0);
        boolean useEnvelope = !settings.has("envelope") || settings.get("envelope").getAsBoolean();
        JsonObject layerOverrides = settings.has("layers") ? settings.getAsJsonObject("layers") : new JsonObject();

        List<String> trackNames = new ArrayList<>();
        if (settings.has("tracks")) {
            for (var element : settings.getAsJsonArray("tracks")) {
                trackNames.add(element.getAsString());
            }
        } else {
            for (ShotStyle style : ShotStyle.values()) {
                if (style.exported()) {
                    trackNames.add(style.name());
                }
            }
        }

        // --- анимация: порядок блоков и синтетические тики -------------------------------

        List<Pos> blockOrder = new ArrayList<>();     // все блоки в порядке появления
        List<Integer> blockLayer = new ArrayList<>();
        List<Integer> blockPalette = new ArrayList<>();
        List<List<Pos>> allSteps = new ArrayList<>();
        List<Integer> stepTicksList = new ArrayList<>();
        JsonArray layersOut = new JsonArray();
        JsonArray warnings = new JsonArray();

        Map<Pos, Integer> paletteOf = new HashMap<>();
        List<Pos> everything = new ArrayList<>();
        for (LoadedSchematic.Layer layer : schematic.layers) {
            for (int i = 0; i < layer.blocks().size(); i++) {
                paletteOf.put(layer.blocks().get(i), layer.paletteIndices().get(i));
            }
            everything.addAll(layer.blocks());
        }

        int cursor = 0;
        for (int li = 0; li < schematic.layers.size(); li++) {
            LoadedSchematic.Layer layer = schematic.layers.get(li);
            if (layer.blocks().isEmpty()) {
                continue;
            }
            JsonObject orderJson = layerOverrides.has(String.valueOf(li))
                    ? layerOverrides.getAsJsonObject(String.valueOf(li))
                    : layer.orderJson();
            OrderConfig config = OrderConfig.fromJson(orderJson);
            if (!config.isValid()) {
                warnings.add("Слой «" + layer.name() + "»: " + config.firstError()
                        + " — порядок взят запасной (снизу вверх)");
            }

            List<List<Pos>> steps = BlockOrderer.orderIntoSteps(layer.blocks(), config, layer.seeds());
            if (steps.isEmpty()) {
                continue;
            }
            // Каждому шагу — его длительность из настроек слоя; ноль тиков означает
            // «мгновенно», но тикам камер нужен ход времени, поэтому минимум один.
            int perStep = Math.max(1, config.ticksPerStep());
            int startTick = cursor;
            if (stepTicksList.isEmpty()) {
                stepTicksList.add(startTick);
            }
            for (List<Pos> step : steps) {
                cursor += perStep;
                stepTicksList.add(cursor);
                for (Pos pos : step) {
                    blockOrder.add(pos);
                    blockLayer.add(li);
                    Integer palette = paletteOf.get(pos);
                    blockPalette.add(palette == null ? 0 : palette);
                }
            }
            allSteps.addAll(steps);

            JsonObject layerOut = new JsonObject();
            layerOut.addProperty("index", li);
            layerOut.addProperty("name", layer.name());
            layerOut.addProperty("color", layer.color());
            layerOut.addProperty("startTick", startTick);
            layerOut.addProperty("endTick", cursor);
            layersOut.add(layerOut);

            cursor += layer.pauseAfterTicks();
        }

        JsonObject out = new JsonObject();
        out.addProperty("name", schematic.name);
        out.add("warnings", warnings);
        out.add("layers", layersOut);
        if (allSteps.isEmpty()) {
            out.add("tracks", new JsonArray());
            return out;
        }

        int[] stepTicks = new int[stepTicksList.size()];
        for (int i = 0; i < stepTicks.length; i++) {
            stepTicks[i] = stepTicksList.get(i);
        }

        // --- общие структуры камерного конвейера, как в CameraExport ---------------------

        List<BuildAnalyzer.WorkSegment> segments = BuildAnalyzer.analyze(allSteps, stepTicks);
        List<Set<Pos>> builtBeforeStep = new ArrayList<>(allSteps.size());
        {
            Set<Pos> progressive = new HashSet<>();
            for (List<Pos> step : allSteps) {
                builtBeforeStep.add(Set.copyOf(progressive));
                progressive.addAll(step);
            }
        }
        BuildEnvelope envelope = useEnvelope ? BuildEnvelope.around(everything) : null;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos pos : everything) {
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }

        Map<Pos, Integer> blockIndex = new HashMap<>();
        for (int i = 0; i < blockOrder.size(); i++) {
            blockIndex.put(blockOrder.get(i), i);
        }

        // --- дорожки ---------------------------------------------------------------------

        JsonArray tracksOut = new JsonArray();
        for (String trackName : trackNames) {
            ShotStyle style;
            try {
                style = ShotStyle.valueOf(trackName);
            } catch (IllegalArgumentException e) {
                warnings.add("Неизвестная дорожка: " + trackName);
                continue;
            }
            tracksOut.add(style.wholeBuild()
                    ? masterTrack(style, everything, stepTicks[0], fov, aspect, envelope)
                    : sceneTrack(style, segments, builtBeforeStep, allSteps, blockIndex,
                            fov, aspect, envelope, minX, minY, minZ, maxX, maxY, maxZ));
        }
        out.add("tracks", tracksOut);

        // --- геометрия для просмотрщика --------------------------------------------------

        JsonArray positions = new JsonArray();
        JsonArray palettes = new JsonArray();
        JsonArray layerOf = new JsonArray();
        for (int i = 0; i < blockOrder.size(); i++) {
            Pos pos = blockOrder.get(i);
            positions.add(pos.x());
            positions.add(pos.y());
            positions.add(pos.z());
            palettes.add(blockPalette.get(i));
            layerOf.add(blockLayer.get(i));
        }
        out.add("blockPositions", positions);
        out.add("blockPalette", palettes);
        out.add("blockLayer", layerOf);

        JsonArray paletteOut = new JsonArray();
        for (String state : schematic.palette) {
            paletteOut.add(state);
        }
        out.add("palette", paletteOut);

        JsonArray stepStarts = new JsonArray();
        JsonArray stepTicksOut = new JsonArray();
        int placed = 0;
        for (int i = 0; i < allSteps.size(); i++) {
            stepStarts.add(placed);
            stepTicksOut.add(stepTicks[i]);
            placed += allSteps.get(i).size();
        }
        stepStarts.add(placed);
        stepTicksOut.add(stepTicks[allSteps.size()]);
        out.add("stepStarts", stepStarts);
        out.add("stepTicks", stepTicksOut);

        out.add("bbox", box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
        return out;
    }

    /** Общий план на всю постройку — один кадр на всю запись. */
    private static JsonObject masterTrack(ShotStyle style, List<Pos> everything, int startTick,
                                          double fov, double aspect, BuildEnvelope envelope) {
        CameraShot shot = ShotPlanner.plan(everything,
                List.of(new ShotPlanner.VisibilityCheck(everything, Set.of())), style, fov,
                startTick, Double.NaN, List.of(), List.of(), aspect, envelope).shot();
        JsonObject track = trackHeader(style);
        JsonArray shots = new JsonArray();
        shots.add(shotJson(shot.withCut(true)));
        track.add("shots", shots);
        track.add("scenes", new JsonArray());
        track.add("metrics", new JsonObject());
        return track;
    }

    /** Дорожка по сценам — ровно та же цепочка решений, что в CameraExport.planTracks. */
    private static JsonObject sceneTrack(ShotStyle style, List<BuildAnalyzer.WorkSegment> segments,
                                         List<Set<Pos>> builtBeforeStep, List<List<Pos>> allSteps,
                                         Map<Pos, Integer> blockIndex,
                                         double fov, double aspect, BuildEnvelope envelope,
                                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List<ScenePlanner.Scene> scenes = ScenePlanner.splitWhatCannotBeSeen(
                ScenePlanner.buildScenes(segments, style.policy()),
                builtBeforeStep, style, fov, style.policy(), envelope, aspect);

        // снимок стоящего на начало каждой сцены — здесь только блоки схемы, мира нет
        List<Set<Pos>> solidAtStart = new ArrayList<>();
        {
            Set<Pos> solid = new HashSet<>();
            int cursor = 0;
            for (int gs = 0; gs < allSteps.size(); gs++) {
                while (cursor < scenes.size() && scenes.get(cursor).firstGlobalStep() == gs) {
                    solidAtStart.add(new HashSet<>(solid));
                    cursor++;
                }
                solid.addAll(allSteps.get(gs));
            }
            while (solidAtStart.size() < scenes.size()) {
                solidAtStart.add(new HashSet<>(solid));
            }
        }

        JsonObject track = trackHeader(style);
        JsonArray shotsOut = new JsonArray();
        JsonArray scenesOut = new JsonArray();

        int totalBlocks = 0, occludedBlocks = 0, outOfFrame = 0, outOfSafe = 0;
        int insideHouse = 0, shotCount = 0;
        JsonArray occludedIdx = new JsonArray();
        JsonArray outIdx = new JsonArray();

        double previousAzimuth = Double.NaN;
        CameraShot previousShot = null;
        double lastKnownDirection = Double.NaN;

        for (int i = 0; i < scenes.size(); i++) {
            ScenePlanner.Scene scene = scenes.get(i);
            double direction = Double.isNaN(scene.direction()) && scene.shape() == BuildAnalyzer.Shape.VERTICAL
                    ? lastKnownDirection : scene.direction();
            List<Double> anchors = Double.isNaN(direction) ? List.of()
                    : List.of(norm(direction + 90), norm(direction + 270));

            boolean interior = envelope != null
                    && !ScenePlanner.visibleFromOutside(scene, builtBeforeStep, style, fov, envelope);
            BuildEnvelope sceneEnvelope = interior ? null : envelope;

            List<ShotPlanner.VisibilityCheck> checks = ScenePlanner.timeSlices(scene, builtBeforeStep);
            ShotPlanner.Placement placement = ShotPlanner.plan(scene.blocks(), checks, style, fov,
                    scene.startTick(), previousAzimuth, List.of(), anchors, aspect, sceneEnvelope);
            List<BuildTimeline.FrontSample> front = style.follows()
                    ? BuildTimeline.sample(scene.steps(), scene.startTick(), scene.endTick() - 1)
                    : List.of();
            List<CameraShot> shots = ShotPlanner.followShots(scene.blocks(), placement, style, front,
                    scene.endTick() - 1, solidAtStart.get(i), sceneEnvelope);
            if (!Double.isNaN(scene.direction())) {
                lastKnownDirection = scene.direction();
            }
            if (shots.isEmpty()) {
                continue;
            }
            boolean hardCut = previousShot == null || !style.follows()
                    || !SafetyValidator.isTrajectorySafe(
                            new double[]{previousShot.x(), previousShot.y(), previousShot.z()},
                            new double[]{shots.get(0).x(), shots.get(0).y(), shots.get(0).z()},
                            solidAtStart.get(i));
            shots.set(0, shots.get(0).withCut(hardCut));
            previousAzimuth = placement.azimuth();
            previousShot = shots.get(shots.size() - 1);

            for (CameraShot shot : shots) {
                shotsOut.add(shotJson(shot));
                shotCount++;
                boolean inXZ = shot.x() >= minX && shot.x() <= maxX + 1
                        && shot.z() >= minZ && shot.z() <= maxZ + 1;
                if (inXZ && shot.y() <= maxY + 1) {
                    insideHouse++;
                }
            }

            JsonObject sceneOut = new JsonObject();
            sceneOut.addProperty("startTick", scene.startTick());
            sceneOut.addProperty("endTick", scene.endTick());
            sceneOut.addProperty("shape", scene.shape().name());
            sceneOut.addProperty("interior", interior);
            sceneOut.addProperty("blocks", scene.blocks().size());
            sceneOut.addProperty("azimuth", Math.round(placement.azimuth()));
            scenesOut.add(sceneOut);

            // метрика по каждому блоку в момент его установки — из активного кадра
            int sceneSteps = scene.steps().size();
            for (int si = 0; si < sceneSteps; si++) {
                int globalStep = scene.firstGlobalStep() + si;
                if (globalStep >= allSteps.size()) {
                    break;
                }
                int tick = scene.startTick()
                        + (int) ((long) si * (scene.endTick() - scene.startTick()) / Math.max(1, sceneSteps));
                CameraShot active = shots.get(0);
                for (CameraShot shot : shots) {
                    if (shot.tick() <= tick) {
                        active = shot;
                    }
                }
                double[] camera = {active.x(), active.y(), active.z()};
                Set<Pos> before = builtBeforeStep.get(globalStep);
                for (Pos block : allSteps.get(globalStep)) {
                    totalBlocks++;
                    Integer index = blockIndex.get(block);
                    if (!Occlusion.isVisible(camera, block, before)) {
                        occludedBlocks++;
                        if (index != null) {
                            occludedIdx.add(index);
                        }
                    }
                    double[] screen = project(active, block, fov, aspect);
                    boolean out = screen == null
                            || Math.abs(screen[0]) > 1.0 || Math.abs(screen[1]) > 1.0;
                    boolean outSafe = screen == null
                            || Math.abs(screen[0]) > 0.8 || Math.abs(screen[1]) > 0.8;
                    if (out) {
                        outOfFrame++;
                        if (index != null) {
                            outIdx.add(index);
                        }
                    }
                    if (outSafe) {
                        outOfSafe++;
                    }
                }
            }
        }

        track.add("shots", shotsOut);
        track.add("scenes", scenesOut);

        JsonObject metrics = new JsonObject();
        metrics.addProperty("blocks", totalBlocks);
        metrics.addProperty("occludedPct", pct(occludedBlocks, totalBlocks));
        metrics.addProperty("outOfFramePct", pct(outOfFrame, totalBlocks));
        metrics.addProperty("outOfSafePct", pct(outOfSafe, totalBlocks));
        metrics.addProperty("shots", shotCount);
        metrics.addProperty("insideHouse", insideHouse);
        metrics.addProperty("insideHousePct", pct(insideHouse, shotCount));
        metrics.add("occludedBlocks", occludedIdx);
        metrics.add("outOfFrameBlocks", outIdx);
        track.add("metrics", metrics);
        return track;
    }

    private static JsonObject trackHeader(ShotStyle style) {
        JsonObject track = new JsonObject();
        track.addProperty("style", style.name());
        track.addProperty("displayName", style.displayName());
        track.addProperty("colour", style.trackColour());
        track.addProperty("follows", style.follows());
        return track;
    }

    private static JsonObject shotJson(CameraShot shot) {
        JsonObject json = new JsonObject();
        json.addProperty("tick", shot.tick());
        json.addProperty("x", shot.x());
        json.addProperty("y", shot.y());
        json.addProperty("z", shot.z());
        json.addProperty("yaw", shot.yaw());
        json.addProperty("pitch", shot.pitch());
        json.addProperty("cut", shot.cut());
        return json;
    }

    /** Экранные координаты блока в [-1,1], либо {@code null}, если он позади камеры. */
    private static double[] project(CameraShot shot, Pos block, double fovDegrees, double aspect) {
        double[] camera = {shot.x(), shot.y(), shot.z()};
        double[] forward = CameraFraming.direction(shot.yaw(), shot.pitch());
        double[] right = cross(forward, new double[]{0, 1, 0});
        double length = Math.sqrt(dot(right, right));
        if (length < 1.0e-9) {
            return null;
        }
        right = new double[]{right[0] / length, right[1] / length, right[2] / length};
        double[] up = cross(right, forward);
        double[] offset = {block.x() + 0.5 - camera[0], block.y() + 0.5 - camera[1], block.z() + 0.5 - camera[2]};
        double depth = dot(offset, forward);
        if (depth <= 1.0e-6) {
            return null;
        }
        double half = Math.tan(Math.toRadians(fovDegrees / 2));
        return new double[]{dot(offset, right) / (depth * half * aspect), dot(offset, up) / (depth * half)};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double pct(int part, int total) {
        return total == 0 ? 0 : Math.round(1000.0 * part / total) / 10.0;
    }

    private static double norm(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    private static double get(JsonObject json, String key, double fallback) {
        return json.has(key) ? json.get(key).getAsDouble() : fallback;
    }

    private static JsonArray box(double x1, double y1, double z1, double x2, double y2, double z2) {
        JsonArray box = new JsonArray();
        box.add(x1);
        box.add(y1);
        box.add(z1);
        box.add(x2);
        box.add(y2);
        box.add(z2);
        return box;
    }
}

package com.tutorialschematic.lab;

import com.tutorialschematic.camera.BuildAnalyzer;
import com.tutorialschematic.camera.BuildEnvelope;
import com.tutorialschematic.camera.BuildTimeline;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.SafetyValidator;
import com.tutorialschematic.camera.ScenePlanner;
import com.tutorialschematic.camera.ShotPlanner;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.order.Pos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Прогон камер по схеме — тот же конвейер, что и в моде, но без игры.
 *
 * <p><b>Внимание.</b> Этот класс повторяет {@code CameraExport.planTracks} из мода. Сами
 * алгоритмы не дублируются — {@link BuildAnalyzer}, {@link ScenePlanner}, {@link ShotPlanner}
 * подключены исходниками из мода и здесь ровно те же. Дублируется только порядок вызовов,
 * потому что в моде он намертво сцеплен с классами Minecraft (мир, метки реплея, настройки
 * игрока). <b>Правя порядок вызовов в моде, поправьте и здесь</b>, иначе лаборатория начнёт
 * показывать не то, что уходит в реплей, — а это ровно та ошибка, из-за которой раньше
 * «по числам хорошо, глазами плохо».
 *
 * <p>Отличий от мода два, и оба намеренные: тики берутся из настроек темпа схемы, а не из
 * меток реплея (записи тут нет), и посторонние заслоны из мира не сканируются — в
 * лаборатории нет мира, только сама постройка.
 */
public final class LabPipeline {

    /** Результат прогона одной дорожки. */
    public record Track(ShotStyle style, List<ScenePlanner.Scene> scenes, List<CameraShot> shots) {

        /** Кадр, действующий на этот тик: последний, чей тик уже наступил. */
        public CameraShot shotAt(int tick) {
            CameraShot active = null;
            for (CameraShot shot : shots) {
                if (shot.tick() <= tick) {
                    active = shot;
                } else {
                    break;
                }
            }
            return active == null && !shots.isEmpty() ? shots.get(0) : active;
        }

        /** Номер сцены, идущей на этот тик, либо -1. */
        public int sceneAt(int tick) {
            for (int i = scenes.size() - 1; i >= 0; i--) {
                if (scenes.get(i).startTick() <= tick) {
                    return i;
                }
            }
            return scenes.isEmpty() ? -1 : 0;
        }
    }

    private final LabSchematic schematic;
    private final double fov;
    private final double aspect;
    private final BuildEnvelope envelope;
    private final List<Set<Pos>> builtBeforeStep;
    private final List<BuildAnalyzer.WorkSegment> segments;
    private final Map<ShotStyle, Track> tracks = new EnumMap<>(ShotStyle.class);

    public LabPipeline(LabSchematic schematic, double fov, double aspect) {
        this.schematic = schematic;
        this.fov = fov;
        this.aspect = aspect;

        List<List<Pos>> allSteps = schematic.allSteps();
        this.envelope = schematic.everything().isEmpty() ? null : BuildEnvelope.around(schematic.everything());

        this.builtBeforeStep = new ArrayList<>(allSteps.size());
        Set<Pos> progressive = new HashSet<>();
        for (List<Pos> step : allSteps) {
            builtBeforeStep.add(Set.copyOf(progressive));
            progressive.addAll(step);
        }

        this.segments = BuildAnalyzer.analyze(allSteps, schematic.stepTicks());
        for (ShotStyle style : ShotStyle.values()) {
            if (!style.exported()) {
                continue;
            }
            tracks.put(style, style.wholeBuild() ? masterTrack(style) : plan(style, segments));
        }
    }

    public BuildEnvelope envelope() {
        return envelope;
    }

    /** Как {@link BuildAnalyzer} разобрал постройку на куски работы — общее для всех дорожек. */
    public List<BuildAnalyzer.WorkSegment> segments() {
        return segments;
    }

    public List<Set<Pos>> builtBeforeStep() {
        return builtBeforeStep;
    }

    public Track track(ShotStyle style) {
        return tracks.get(style);
    }

    public List<ShotStyle> exportedStyles() {
        return new ArrayList<>(tracks.keySet());
    }

    /** Общий план: один кадр на всю запись, кадрируется по всей постройке. */
    private Track masterTrack(ShotStyle style) {
        List<Pos> everything = schematic.everything();
        if (everything.isEmpty()) {
            return new Track(style, List.of(), List.of());
        }
        ShotPlanner.Placement placement = ShotPlanner.plan(everything,
                List.of(new ShotPlanner.VisibilityCheck(everything, Set.of())), style, fov,
                schematic.stepTicks()[0], Double.NaN, List.of(), List.of(), aspect, envelope);
        return new Track(style, List.of(), List.of(placement.shot()));
    }

    private Track plan(ShotStyle style, List<BuildAnalyzer.WorkSegment> segments) {
        List<List<Pos>> allSteps = schematic.allSteps();

        List<ScenePlanner.Scene> scenes = ScenePlanner.splitWhatCannotBeSeen(
                ScenePlanner.buildScenes(segments, style.policy()),
                builtBeforeStep, style, fov, style.policy(), envelope);

        // Снимок «твёрдого» на начало каждой сцены. Мира здесь нет, поэтому это только блоки
        // самой схемы — в моде сюда добавляется ещё и просканированное окружение.
        List<Set<Pos>> solidAtStart = new ArrayList<>(scenes.size());
        Set<Pos> solid = new HashSet<>();
        int cursor = 0;
        for (int globalStep = 0; globalStep < allSteps.size(); globalStep++) {
            while (cursor < scenes.size() && scenes.get(cursor).firstGlobalStep() == globalStep) {
                solidAtStart.add(new HashSet<>(solid));
                cursor++;
            }
            solid.addAll(allSteps.get(globalStep));
        }
        while (solidAtStart.size() < scenes.size()) {
            solidAtStart.add(new HashSet<>(solid));
        }

        List<CameraShot> shots = new ArrayList<>();
        double previousAzimuth = Double.NaN;
        CameraShot previousShot = null;
        double lastKnownDirection = Double.NaN;

        for (int i = 0; i < scenes.size(); i++) {
            ScenePlanner.Scene scene = scenes.get(i);
            double direction = Double.isNaN(scene.direction())
                    && scene.shape() == BuildAnalyzer.Shape.VERTICAL
                    ? lastKnownDirection : scene.direction();

            BuildEnvelope keepOutsideOf = envelope != null
                    && !ScenePlanner.visibleFromOutside(scene, builtBeforeStep, style, fov, envelope)
                    ? null : envelope;

            List<Double> anchors = Double.isNaN(direction)
                    ? List.of()
                    : List.of(norm(direction + 90), norm(direction + 270));

            ShotPlanner.Placement placement = ShotPlanner.plan(scene.blocks(),
                    ScenePlanner.timeSlices(scene, builtBeforeStep), style, fov,
                    scene.startTick(), previousAzimuth, List.of(), anchors, aspect, keepOutsideOf);

            List<BuildTimeline.FrontSample> front = style.follows()
                    ? BuildTimeline.sample(scene.steps(), scene.startTick(), scene.endTick() - 1)
                    : List.of();
            List<CameraShot> sceneShots = ShotPlanner.followShots(scene.blocks(), placement, style,
                    front, scene.endTick() - 1, solidAtStart.get(i), keepOutsideOf);

            if (!Double.isNaN(scene.direction())) {
                lastKnownDirection = scene.direction();
            }
            if (sceneShots.isEmpty()) {
                continue;
            }
            boolean hardCut = previousShot == null || !style.follows()
                    || !SafetyValidator.isTrajectorySafe(
                            new double[]{previousShot.x(), previousShot.y(), previousShot.z()},
                            new double[]{sceneShots.get(0).x(), sceneShots.get(0).y(), sceneShots.get(0).z()},
                            solidAtStart.get(i));
            List<CameraShot> copy = new ArrayList<>(sceneShots);
            copy.set(0, copy.get(0).withCut(hardCut));
            shots.addAll(copy);

            previousAzimuth = placement.azimuth();
            previousShot = copy.get(copy.size() - 1);
        }
        return new Track(style, scenes, shots);
    }

    private static double norm(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }
}

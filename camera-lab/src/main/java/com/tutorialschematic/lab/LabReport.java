package com.tutorialschematic.lab;

import com.tutorialschematic.camera.CameraFraming;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.Occlusion;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.order.Pos;

import java.util.List;
import java.util.Set;

/**
 * Числовой отчёт по прогону — то же, что раньше считалось одноразовыми пробниками.
 *
 * <p>Мерится <b>в момент установки каждого блока</b>, а не по сцене целиком. Это важно:
 * метрика «видимость сцены из точки, выбранной на её старте» несколько итераций подряд
 * показывала отличные числа при откровенно плохой картинке, потому что не учитывала ни
 * блоки, вставшие позже внутри той же сцены, ни то, что кадр обрезает по краям.
 */
public final class LabReport {

    private LabReport() {
    }

    public static String build(LabSchematic schematic, LabPipeline pipeline, double fov, double aspect) {
        StringBuilder out = new StringBuilder();
        out.append("Схема: ").append(schematic.name())
                .append("   блоков ").append(schematic.everything().size())
                .append(", шагов ").append(schematic.allSteps().size())
                .append(", тиков ").append(schematic.totalTicks()).append('\n');
        out.append("Формат кадра: ").append(String.format("%.3f", aspect))
                .append("   FOV ").append(String.format("%.0f", fov)).append('\n');
        out.append("Мерится в момент установки каждого блока.\n\n");

        int[] box = boundsOf(schematic.everything());
        for (ShotStyle style : pipeline.exportedStyles()) {
            LabPipeline.Track track = pipeline.track(style);
            if (track == null || track.shots().isEmpty()) {
                continue;
            }
            int total = 0, hidden = 0, outOfFrame = 0, outOfSafe = 0;
            int shotsInside = 0;

            for (int step = 0; step < schematic.allSteps().size(); step++) {
                int tick = schematic.stepTicks()[step];
                CameraShot shot = track.shotAt(tick);
                if (shot == null) {
                    continue;
                }
                double[] camera = {shot.x(), shot.y(), shot.z()};
                double[] direction = CameraFraming.direction(shot.yaw(), shot.pitch());
                double[] lookAt = {camera[0] + direction[0], camera[1] + direction[1], camera[2] + direction[2]};
                Set<Pos> standing = pipeline.builtBeforeStep().get(step);

                for (Pos pos : schematic.allSteps().get(step)) {
                    total++;
                    if (!Occlusion.isVisible(camera, pos, standing)) {
                        hidden++;
                    }
                    double[] point = {pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5};
                    double[] screen = CameraFraming.project(camera, lookAt, point, fov, aspect);
                    if (screen == null || Math.abs(screen[0]) > 1 || Math.abs(screen[1]) > 1) {
                        outOfFrame++;
                    } else if (Math.abs(screen[0]) > style.safeZone() || Math.abs(screen[1]) > style.safeZone()) {
                        outOfSafe++;
                    }
                }
            }
            for (CameraShot shot : track.shots()) {
                if (shot.x() >= box[0] && shot.x() <= box[3] + 1
                        && shot.z() >= box[2] && shot.z() <= box[5] + 1
                        && shot.y() <= box[4] + 1) {
                    shotsInside++;
                }
            }

            out.append(style.displayName()).append('\n');
            out.append(String.format("    сцен %d, кадров %d%n", track.scenes().size(), track.shots().size()));
            out.append(String.format("    заслонено при установке : %4d из %d  (%.1f%%)%n",
                    hidden, total, percent(hidden, total)));
            out.append(String.format("    за кадром               : %4d из %d  (%.1f%%)%n",
                    outOfFrame, total, percent(outOfFrame, total)));
            out.append(String.format("    вне безопасной зоны     : %4d из %d  (%.1f%%)%n",
                    outOfSafe, total, percent(outOfSafe, total)));
            out.append(String.format("    камера внутри постройки : %4d из %d кадров (%.0f%%)%n%n",
                    shotsInside, track.shots().size(), percent(shotsInside, track.shots().size())));
        }
        out.append("Цель: заслонено и за кадром — близко к нулю; камера внутри постройки —\n")
                .append("близко к нулю для внешних слоёв и близко к 100% для интерьера.");
        return out.toString();
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]} по всем блокам схемы. */
    private static int[] boundsOf(List<Pos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos pos : blocks) {
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }
}

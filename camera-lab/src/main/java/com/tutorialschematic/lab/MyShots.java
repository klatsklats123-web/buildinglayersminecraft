package com.tutorialschematic.lab;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.ScenePlanner;
import com.tutorialschematic.camera.ShotPlanner;
import com.tutorialschematic.camera.ShotStyle;
import com.tutorialschematic.order.Pos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Ракурсы, поставленные человеком: то, как надо.
 *
 * <p>Ракурс живёт на своём тике и ничем не привязан к тому, что выбрал алгоритм: его можно
 * создать где угодно, а не только поправить существующий. Но при записи в файл рядом с каждым
 * всё равно кладётся ракурс алгоритма <b>на этот же момент</b> и посчитанная разница между
 * ними — именно разница, сопоставленная со слоем и формой, показывает закономерность, а не
 * абсолютные координаты.
 */
public final class MyShots {

    /** Один ракурс, поставленный руками. */
    public record Shot(int tick, double x, double y, double z, float yaw, float pitch,
                       boolean cut, String note) {

        public CameraShot toCamera() {
            return new CameraShot(tick, x, y, z, yaw, pitch, cut);
        }

        public Shot withNote(String note) {
            return new Shot(tick, x, y, z, yaw, pitch, cut, note);
        }

        public Shot withCut(boolean cut) {
            return new Shot(tick, x, y, z, yaw, pitch, cut, note);
        }

        public Shot atTick(int tick) {
            return new Shot(tick, x, y, z, yaw, pitch, cut, note);
        }
    }

    private final TreeMap<Integer, Shot> byTick = new TreeMap<>();

    public void put(Shot shot) {
        byTick.put(shot.tick(), shot);
    }

    /** Переносит ракурс на другой тик, не создавая дубликата на старом месте. */
    public void move(int fromTick, int toTick) {
        Shot shot = byTick.remove(fromTick);
        if (shot != null) {
            byTick.put(toTick, shot.atTick(toTick));
        }
    }

    public void remove(int tick) {
        byTick.remove(tick);
    }

    public Shot get(int tick) {
        return byTick.get(tick);
    }

    public List<Shot> list() {
        return new ArrayList<>(byTick.values());
    }

    public int size() {
        return byTick.size();
    }

    public boolean isEmpty() {
        return byTick.isEmpty();
    }

    /** Ракурс, действующий на этот момент: последний из поставленных до него. */
    public Shot activeAt(int tick) {
        var entry = byTick.floorEntry(tick);
        return entry == null ? null : entry.getValue();
    }

    // ---- файл ----

    /** У каждой дорожки свой файл: ракурсы у них разные, сравнивать между собой нечего. */
    public static Path fileFor(LabSchematic schematic, ShotStyle style) {
        String schema = safe(schematic.name());
        String track = safe(style.name().toLowerCase());
        return Path.of("shots", schema + "__" + track + ".json").toAbsolutePath();
    }

    private static String safe(String text) {
        return text.replaceAll("[^\\p{L}\\p{N}_-]", "_");
    }

    public void save(Path path, LabSchematic schematic, ShotStyle style,
                     LabPipeline.Track track) throws IOException {
        double[] centre = ShotPlanner.centerOf(schematic.everything());
        int[] box = bounds(schematic.everything());

        JsonArray array = new JsonArray();
        for (Shot shot : byTick.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("tick", shot.tick());
            item.addProperty("cut", shot.cut());

            LabSchematic.Layer layer = layerAt(schematic, shot.tick());
            if (layer != null) {
                item.addProperty("layer", layer.name());
            }
            int sceneIndex = track == null ? -1 : track.sceneAt(shot.tick());
            if (track != null && sceneIndex >= 0 && sceneIndex < track.scenes().size()) {
                ScenePlanner.Scene scene = track.scenes().get(sceneIndex);
                item.addProperty("sceneShape", String.valueOf(scene.shape()));
                item.addProperty("sceneBlocks", scene.blocks().size());
                if (!Double.isNaN(scene.direction())) {
                    item.addProperty("sceneFrontDirection", round(scene.direction()));
                }
            }
            if (shot.note() != null && !shot.note().isBlank()) {
                item.addProperty("note", shot.note());
            }

            item.add("mine", describe(shot.toCamera(), centre, box));
            CameraShot algorithm = track == null ? null : track.shotAt(shot.tick());
            if (algorithm != null) {
                item.add("algorithmAtSameMoment", describe(algorithm, centre, box));
                item.add("difference", difference(algorithm, shot.toCamera(), centre));
            }
            array.add(item);
        }

        JsonObject root = new JsonObject();
        root.addProperty("schematic", schematic.name());
        root.addProperty("track", style.displayName());
        root.addProperty("about", "mine — ракурс, поставленный человеком; algorithmAtSameMoment — "
                + "что на этот же момент выбрал алгоритм; difference — разница между ними. "
                + "Блоки derived и difference считаются при сохранении, править их руками не нужно. "
                + "Закономерность искать в difference, сопоставляя с layer и sceneShape.");
        root.addProperty("buildCentre",
                round(centre[0]) + ", " + round(centre[1]) + ", " + round(centre[2]));
        root.addProperty("buildBounds", "X[" + box[0] + ".." + box[3] + "] Y[" + box[1] + ".."
                + box[4] + "] Z[" + box[2] + ".." + box[5] + "]");
        root.addProperty("myShots", byTick.size());
        if (track != null) {
            root.addProperty("algorithmShots", track.shots().size());
        }
        root.add("shots", array);

        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                StandardCharsets.UTF_8);
    }

    private static JsonObject describe(CameraShot shot, double[] centre, int[] box) {
        JsonObject item = new JsonObject();
        item.addProperty("x", round(shot.x()));
        item.addProperty("y", round(shot.y()));
        item.addProperty("z", round(shot.z()));
        item.addProperty("yaw", round(shot.yaw()));
        item.addProperty("pitch", round(shot.pitch()));

        double dx = shot.x() - centre[0];
        double dy = shot.y() - centre[1];
        double dz = shot.z() - centre[2];
        double flat = Math.hypot(dx, dz);
        double azimuth = Math.toDegrees(Math.atan2(dx, dz));

        JsonObject derived = new JsonObject();
        derived.addProperty("azimuthFromCentre", round(azimuth < 0 ? azimuth + 360 : azimuth));
        derived.addProperty("elevationFromCentre", round(Math.toDegrees(Math.atan2(dy, flat))));
        derived.addProperty("distanceFromCentre", round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
        derived.addProperty("heightAboveGround", round(shot.y() - box[1]));
        derived.addProperty("insideBuilding", inside(shot, box));
        item.add("derived", derived);
        return item;
    }

    private static JsonObject difference(CameraShot algorithm, CameraShot mine, double[] centre) {
        double algAzimuth = azimuthOf(algorithm, centre);
        double myAzimuth = azimuthOf(mine, centre);
        double algDistance = distanceOf(algorithm, centre);
        double myDistance = distanceOf(mine, centre);

        JsonObject item = new JsonObject();
        item.addProperty("turnedByDegrees", round(shortestTurn(myAzimuth - algAzimuth)));
        item.addProperty("raisedByBlocks", round(mine.y() - algorithm.y()));
        item.addProperty("movedFurtherByBlocks", round(myDistance - algDistance));
        item.addProperty("distanceRatio", round(algDistance < 1.0e-6 ? 0 : myDistance / algDistance));
        item.addProperty("pitchChangeDegrees", round(mine.pitch() - algorithm.pitch()));
        item.addProperty("movedByBlocks", round(Math.sqrt(
                Math.pow(mine.x() - algorithm.x(), 2)
                        + Math.pow(mine.y() - algorithm.y(), 2)
                        + Math.pow(mine.z() - algorithm.z(), 2))));
        return item;
    }

    public static boolean inside(CameraShot shot, int[] box) {
        return shot.x() >= box[0] && shot.x() <= box[3] + 1
                && shot.z() >= box[2] && shot.z() <= box[5] + 1
                && shot.y() <= box[4] + 1;
    }

    public static double azimuthOf(CameraShot shot, double[] centre) {
        double azimuth = Math.toDegrees(Math.atan2(shot.x() - centre[0], shot.z() - centre[2]));
        return azimuth < 0 ? azimuth + 360 : azimuth;
    }

    public static double elevationOf(CameraShot shot, double[] centre) {
        return Math.toDegrees(Math.atan2(shot.y() - centre[1],
                Math.hypot(shot.x() - centre[0], shot.z() - centre[2])));
    }

    public static double distanceOf(CameraShot shot, double[] centre) {
        return Math.sqrt(Math.pow(shot.x() - centre[0], 2)
                + Math.pow(shot.y() - centre[1], 2)
                + Math.pow(shot.z() - centre[2], 2));
    }

    private static double shortestTurn(double degrees) {
        return ((degrees % 360) + 540) % 360 - 180;
    }

    public static MyShots load(Path path) throws IOException {
        MyShots shots = new MyShots();
        if (!Files.exists(path)) {
            return shots;
        }
        JsonObject root = JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        for (var element : root.getAsJsonArray("shots")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject mine = item.getAsJsonObject("mine");
            shots.put(new Shot(
                    item.get("tick").getAsInt(),
                    mine.get("x").getAsDouble(),
                    mine.get("y").getAsDouble(),
                    mine.get("z").getAsDouble(),
                    mine.get("yaw").getAsFloat(),
                    mine.get("pitch").getAsFloat(),
                    !item.has("cut") || item.get("cut").getAsBoolean(),
                    item.has("note") ? item.get("note").getAsString() : ""));
        }
        return shots;
    }

    public static LabSchematic.Layer layerAt(LabSchematic schematic, int tick) {
        LabSchematic.Layer found = null;
        for (LabSchematic.Layer layer : schematic.layers()) {
            if (layer.startTick() <= tick) {
                found = layer;
            }
        }
        return found;
    }

    public static int[] bounds(List<Pos> blocks) {
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

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

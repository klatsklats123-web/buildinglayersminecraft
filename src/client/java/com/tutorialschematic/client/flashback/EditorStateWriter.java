package com.tutorialschematic.client.flashback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.camera.CameraShot;
import com.tutorialschematic.camera.ShotStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Запись готовых камер в состояние редактора Flashback.
 *
 * <p>Флешбек хранит его обычным JSON в {@code flashback/editor_states/<uuid реплея>.json},
 * поэтому API не требуется — файл пишется напрямую. Формат неофициальный: поля взяты из
 * его сериализаторов ({@code CameraKeyframe.TypeAdapter}, {@code Vector3dTypeAdapater}),
 * и при смене их устройства запись просто перестанет подходить.
 *
 * <p>Поэтому два правила. Первое: если файл уже есть, <b>не трогаем</b> — там может быть
 * ручная работа, затирать её нельзя. Второе: писать можно только когда реплей закрыт,
 * иначе Flashback перезапишет нас своим автосохранением, оно у него раз в тридцать секунд.
 */
public final class EditorStateWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Имя сцены, по которому мы узнаём собственную работу. Файл с таким именем сцены
     * перезаписываем свободно — там наши же камеры, и подобрать их заново обычное дело.
     * Переименуйте сцену в редакторе, и мод перестанет её трогать.
     */
    public static final String SCENE_NAME = "Слои постройки";

    private EditorStateWriter() {
    }

    /**
     * Пишет дорожки камер для реплея.
     *
     * @param uuid   идентификатор реплея из его {@code metadata.json}
     * @param tracks по дорожке на доктрину: список ключевых кадров
     * @return путь к записанному файлу либо {@code null}
     */
    public static Path write(String uuid, Map<ShotStyle, List<CameraShot>> tracks) {
        Path path = FlashbackBridge.editorStateFolder().resolve(uuid + ".json");
        if (Files.exists(path) && !isOurs(path) && hasKeyframes(path)) {
            TutorialSchematicMod.LOGGER.warn("В состоянии редактора есть чужие кадры, не трогаем: {}", path);
            return null;
        }

        JsonArray trackArray = new JsonArray();
        for (Map.Entry<ShotStyle, List<CameraShot>> entry : tracks.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            trackArray.add(track(entry.getKey(), entry.getValue()));
        }

        JsonObject scene = new JsonObject();
        scene.addProperty("name", SCENE_NAME);
        scene.add("keyframeTracks", trackArray);
        scene.addProperty("exportStartTicks", -1);
        scene.addProperty("exportEndTicks", -1);
        // Пустая история обязательна. У EditorScene нет конструктора без аргументов,
        // поэтому Gson собирает его в обход инициализаторов полей: не напишем историю
        // здесь — она останется null, и редактор упадёт на первой же правке кадра.
        scene.add("history", emptyHistory());

        JsonArray scenes = new JsonArray();
        scenes.add(scene);

        JsonObject root = new JsonObject();
        root.add("scenes", scenes);
        root.addProperty("sceneIndex", 0);
        root.addProperty("zoomMin", 0.0);
        root.addProperty("zoomMax", 1.0);

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            TutorialSchematicMod.LOGGER.error("Не удалось записать камеры: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Наш ли это файл. Признак — имя сцены: его ставим только мы, и пока игрок его не
     * менял, содержимое можно смело пересобрать заново.
     */
    private static boolean isOurs(Path path) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("scenes")) {
                return false;
            }
            for (var element : root.getAsJsonArray("scenes")) {
                JsonObject scene = element.getAsJsonObject();
                if (scene.has("name") && SCENE_NAME.equals(scene.get("name").getAsString())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Есть ли в файле хоть один ключевой кадр.
     *
     * <p>Пустое состояние Flashback создаёт сам, едва вы открыли реплей, — отказываться
     * из-за такого файла значит не сработать никогда. А вот файл с кадрами трогать нельзя:
     * там может быть ручная работа.
     */
    private static boolean hasKeyframes(Path path) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("scenes")) {
                return false;
            }
            for (var element : root.getAsJsonArray("scenes")) {
                JsonObject scene = element.getAsJsonObject();
                if (!scene.has("keyframeTracks")) {
                    continue;
                }
                for (var trackElement : scene.getAsJsonArray("keyframeTracks")) {
                    JsonObject track = trackElement.getAsJsonObject();
                    if (track.has("keyframesByTick")
                            && !track.getAsJsonObject("keyframesByTick").isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            // не разобрали — считаем, что там что-то ценное, и не лезем
            TutorialSchematicMod.LOGGER.warn("Не удалось прочитать состояние редактора {}: {}",
                    path.getFileName(), e.getMessage());
            return true;
        }
    }

    private static JsonObject emptyHistory() {
        JsonObject history = new JsonObject();
        history.add("entries", new JsonArray());
        history.addProperty("position", 0);
        return history;
    }

    private static JsonObject track(ShotStyle style, List<CameraShot> shots) {
        JsonObject byTick = new JsonObject();
        for (CameraShot shot : shots) {
            byTick.add(Integer.toString(shot.tick()), keyframe(shot, style));
        }

        JsonObject track = new JsonObject();
        // Идентификатор типа в реестре Flashback — заглавными. Строчное "camera" внутри
        // самого кадра к делу не относится: там поле "type", и при чтении оно не смотрится.
        // Промах здесь роняет загрузку всего файла на requireNonNull в KeyframeRegistry.
        track.addProperty("keyframeType", "CAMERA");
        track.add("keyframesByTick", byTick);
        track.addProperty("enabled", true);
        track.addProperty("customName", style.displayName());
        track.addProperty("customColour", style.trackColour());
        return track;
    }

    private static JsonObject keyframe(CameraShot shot, ShotStyle style) {
        JsonArray position = new JsonArray();
        position.add(shot.x());
        position.add(shot.y());
        position.add(shot.z());

        JsonObject keyframe = new JsonObject();
        keyframe.add("position", position);
        keyframe.addProperty("yaw", shot.yaw());
        keyframe.addProperty("pitch", shot.pitch());
        keyframe.addProperty("roll", 0.0f);
        keyframe.addProperty("type", "camera");
        // Статичная доктрина держит кадр до следующего слоя и там режется насухо;
        // пролётная едет между своими двумя кадрами плавно.
        keyframe.addProperty("interpolation_type", style.moving() ? "SMOOTH" : "HOLD");
        return keyframe;
    }
}

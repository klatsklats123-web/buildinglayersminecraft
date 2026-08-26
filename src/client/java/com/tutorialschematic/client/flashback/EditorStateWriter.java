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
        if (Files.exists(path)) {
            TutorialSchematicMod.LOGGER.warn("Состояние редактора для реплея уже есть, не трогаем: {}", path);
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
        scene.addProperty("name", "Слои постройки");
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
        track.addProperty("keyframeType", "camera");
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

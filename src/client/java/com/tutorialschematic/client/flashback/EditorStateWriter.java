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
    public static Path write(String uuid, Map<ShotStyle, List<CameraShot>> tracks,
                             double fov, double aspect, int timeOfDay) {
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
        root.add("replayVisuals", visuals(fov, aspect, timeOfDay));
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

    /**
     * Настройки картинки для этой записи.
     *
     * <p>Раньше мы их не писали вовсе, и два из них расходились с тем, как мы считаем кадр.
     *
     * <p>Угол обзора. Расстояние до стены мы подбираем так, чтобы она целиком влезла в кадр,
     * и для этого нужен угол. Берём его из настроек игры в момент расстановки, а рендер брал
     * заново — уже свой, на момент экспорта. Тронул ползунок между тем и этим, и все камеры
     * стоят не на своих местах. Записываем ровно то значение, по которому считали.
     *
     * <p>Рамка. Кадрируем мы под вертикальные девять на шестнадцать, а по умолчанию у
     * Flashback стоит горизонтальная рамка. Вертикальный кадр выше — значит всё, что мы
     * аккуратно вписали сверху и снизу, при рендере обрезалось.
     *
     * <p>Ещё убираем из кадра панель предметов и, если попросили, держим время суток: за
     * несколько минут постройки солнце уходит достаточно, чтобы свет поплыл посреди ролика.
     *
     * <p>Писать объект частично можно: у {@code ReplayVisuals} есть конструктор без
     * аргументов, поэтому остальные поля Gson оставит при их обычных значениях.
     */
    private static JsonObject visuals(double fov, double aspect, int timeOfDay) {
        JsonObject visuals = new JsonObject();
        visuals.addProperty("overrideFov", true);
        visuals.addProperty("overrideFovAmount", fov);
        visuals.addProperty("changeAspectRatio", aspectRatioName(aspect));
        visuals.addProperty("showHotbar", false);
        if (timeOfDay >= 0) {
            visuals.addProperty("overrideTimeOfDay", timeOfDay);
        }
        return visuals;
    }

    /**
     * Ближайшая из рамок, которые знает Flashback, к тому соотношению, под которое кадрируем.
     *
     * <p>Соотношение живёт в коде подбора кадра, а имя рамки — здесь, и разъехаться им
     * нельзя: имя выбирается по числу, а не вписано рядом.
     */
    private static String aspectRatioName(double aspect) {
        String[] names = {"ASPECT_9_16", "ASPECT_1_1", "ASPECT_3_2", "ASPECT_4_3", "ASPECT_16_9"};
        double[] values = {9.0 / 16.0, 1.0, 3.0 / 2.0, 4.0 / 3.0, 16.0 / 9.0};
        String best = names[names.length - 1];
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < names.length; i++) {
            double distance = Math.abs(values[i] - aspect);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = names[i];
            }
        }
        return best;
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
            byTick.add(Integer.toString(shot.tick()), keyframe(shot));
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

    private static JsonObject keyframe(CameraShot shot) {
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
        // Кадр, открывающий новую фазу постройки (см. CameraShot.cut), режется насухо —
        // ракурс там выбран заново и подъезжать к нему сплайном от старого нельзя. Кадры
        // внутри фазы едут плавно у ведущих и с движением доктрин, а у статичных фаза
        // всегда из одного кадра, так что для них это то же самое, что и раньше.
        keyframe.addProperty("interpolation_type", shot.cut() ? "HOLD" : "SMOOTH");
        return keyframe;
    }
}

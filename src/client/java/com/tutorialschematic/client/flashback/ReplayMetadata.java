package com.tutorialschematic.client.flashback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorialschematic.TutorialSchematicMod;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Чтение {@code metadata.json} из файла реплея.
 *
 * <p>Оттуда берётся ровно две вещи, и обе нам нужны позарез:
 * <ul>
 *   <li>{@code uuid} — по нему называется файл состояния редактора, в который мы пишем камеры;</li>
 *   <li>{@code markers} — метки вместе с <b>номерами тиков</b>, на которые их поставил
 *       Flashback. Свой счётчик тиков он наружу не отдаёт, поэтому метки, которые мы
 *       расставили по слоям во время записи, служат нам опорными точками времени.</li>
 * </ul>
 *
 * <p>Реплей — это zip, поэтому читаем через файловую систему архива, ничего не распаковывая.
 */
public record ReplayMetadata(Path file, String uuid, Map<Integer, String> markers) {

    /** Самый свежий реплей в папке Flashback, либо {@code null}, если их нет. */
    @Nullable
    public static Path newestReplay() {
        Path folder = FlashbackBridge.replayFolder();
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (var stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .max(Comparator.comparingLong(ReplayMetadata::lastModified))
                    .orElse(null);
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.warn("Не удалось прочитать папку реплеев: {}", e.getMessage());
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    @Nullable
    public static ReplayMetadata read(Path replay) {
        URI uri = URI.create("jar:" + replay.toUri());
        try (FileSystem zip = FileSystems.newFileSystem(uri, Map.of())) {
            Path meta = zip.getPath("/metadata.json");
            if (!Files.exists(meta)) {
                return null;
            }
            String json;
            try (InputStream in = Files.newInputStream(meta)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("uuid")) {
                return null;
            }

            Map<Integer, String> markers = new LinkedHashMap<>();
            if (root.has("markers")) {
                JsonObject markerJson = root.getAsJsonObject("markers");
                markerJson.entrySet().stream()
                        // ключ — номер тика строкой; сортируем как числа, а не как текст
                        .sorted(Comparator.comparingInt(entry -> parseTick(entry.getKey())))
                        .forEach(entry -> {
                            int tick = parseTick(entry.getKey());
                            if (tick < 0) {
                                return;
                            }
                            JsonObject marker = entry.getValue().getAsJsonObject();
                            markers.put(tick, marker.has("description")
                                    ? marker.get("description").getAsString() : "");
                        });
            }
            return new ReplayMetadata(replay, root.get("uuid").getAsString(), markers);
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.warn("Не удалось прочитать метаданные реплея {}: {}",
                    replay.getFileName(), e.getMessage());
            return null;
        }
    }

    private static int parseTick(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

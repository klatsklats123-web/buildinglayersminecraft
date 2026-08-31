package com.tutorialschematic.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.order.OrderConfig;
import com.tutorialschematic.schematic.BlockData;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.EntityData;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чтение и запись файлов {@code .ltutorial}.
 *
 * <p>Формат — обычный JSON, чтобы его можно было открыть текстовым редактором и,
 * например, поправить формулу вручную. Состояния блоков лежат в палитре и
 * упоминаются по номеру, а сами блоки — плоским массивом чисел: у постройки на
 * десятки тысяч блоков это разница между файлом на мегабайты и на сотни килобайт.
 *
 * <p>Координаты в файле относительные — отсчёт от минимального угла постройки,
 * поэтому схему можно поставить в любом месте мира.
 */
public final class SchematicFiles {

    public static final String EXTENSION = ".ltutorial";
    /** Версия формата. Повышать при несовместимых изменениях. */
    public static final int FORMAT_VERSION = 4;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SchematicFiles() {
    }

    /** Папка со схемами внутри папки игры. Создаётся при первом обращении. */
    public static Path directory() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("tutorial-schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            TutorialSchematicMod.LOGGER.error("Не удалось создать папку для схем: {}", e.getMessage());
        }
        return dir;
    }

    public static Path pathFor(String fileName) {
        String safe = sanitize(fileName);
        if (!safe.endsWith(EXTENSION)) {
            safe += EXTENSION;
        }
        return directory().resolve(safe);
    }

    /** Имена всех сохранённых схем, свежие сверху. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(directory())) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparingLong(SchematicFiles::lastModified).reversed())
                    .forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            TutorialSchematicMod.LOGGER.error("Не удалось прочитать список схем: {}", e.getMessage());
        }
        return names;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public static boolean delete(String fileName) {
        try {
            return Files.deleteIfExists(pathFor(fileName));
        } catch (IOException e) {
            TutorialSchematicMod.LOGGER.error("Не удалось удалить схему: {}", e.getMessage());
            return false;
        }
    }

    // ---- запись ----

    /**
     * Сохраняет схему. Имя файла берётся из названия схемы, если явное не задано.
     *
     * @return путь к записанному файлу
     */
    public static Path save(TutorialSchematic schematic, String fileName) throws IOException {
        Path path = pathFor(fileName == null || fileName.isBlank() ? schematic.name() : fileName);
        if (schematic.created().isEmpty()) {
            schematic.setCreated(LocalDateTime.now().format(STAMP));
        }

        BlockPos origin = schematic.origin();
        int[] size = schematic.size();

        // Палитра состояний: одинаковые блоки встречаются тысячами, храним их один раз
        List<String> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();

        JsonArray layersJson = new JsonArray();
        for (BuildLayer layer : schematic.layers()) {
            JsonObject layerJson = new JsonObject();
            layerJson.addProperty("id", layer.id());
            layerJson.addProperty("name", layer.name());
            layerJson.addProperty("color", layer.color());
            layerJson.addProperty("startDelayTicks", layer.startDelayTicks());
            layerJson.addProperty("endDelayTicks", layer.endDelayTicks());
            layerJson.add("order", layer.order().toJson());

            JsonArray blocksJson = new JsonArray();
            JsonObject nbtJson = new JsonObject();
            for (Map.Entry<BlockPos, BlockData> entry : layer.blocks().entrySet()) {
                BlockPos pos = entry.getKey();
                BlockData data = entry.getValue();

                int rx = pos.getX() - origin.getX();
                int ry = pos.getY() - origin.getY();
                int rz = pos.getZ() - origin.getZ();

                Integer index = paletteIndex.get(data.state());
                if (index == null) {
                    index = palette.size();
                    palette.add(data.serializeState());
                    paletteIndex.put(data.state(), index);
                }

                blocksJson.add(rx);
                blocksJson.add(ry);
                blocksJson.add(rz);
                blocksJson.add(index);

                String snbt = data.serializeNbt();
                if (snbt != null) {
                    nbtJson.addProperty(rx + "," + ry + "," + rz, snbt);
                }
            }
            layerJson.add("blocks", blocksJson);
            if (!nbtJson.isEmpty()) {
                layerJson.add("nbt", nbtJson);
            }

            // декорации: картины и рамки не лежат в сетке блоков, поэтому пишутся отдельно
            // и с дробными координатами — округление сдвинуло бы их на полблока
            if (layer.entityCount() > 0) {
                JsonArray entitiesJson = new JsonArray();
                for (EntityData data : layer.entities().values()) {
                    JsonObject entityJson = new JsonObject();
                    entityJson.addProperty("id", data.id().toString());
                    entityJson.addProperty("type", data.typeId());
                    entityJson.addProperty("x", data.x() - origin.getX());
                    entityJson.addProperty("y", data.y() - origin.getY());
                    entityJson.addProperty("z", data.z() - origin.getZ());
                    entityJson.addProperty("nbt", NbtUtils.structureToSnbt(data.nbt()));
                    entitiesJson.add(entityJson);
                }
                layerJson.add("entities", entitiesJson);
            }

            if (!layer.seeds().isEmpty()) {
                JsonArray seedsJson = new JsonArray();
                for (BlockPos pos : layer.seeds()) {
                    seedsJson.add(pos.getX() - origin.getX());
                    seedsJson.add(pos.getY() - origin.getY());
                    seedsJson.add(pos.getZ() - origin.getZ());
                }
                layerJson.add("seeds", seedsJson);
            }
            layersJson.add(layerJson);
        }

        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("name", schematic.name());
        root.addProperty("author", schematic.author());
        root.addProperty("created", schematic.created());

        // Абсолютный угол постройки. Без него схема при открытии ложилась в начало
        // координат мира — за тысячи блоков от того места, где её размечали, и выглядело
        // это как «сохранение потерялось».
        JsonArray originJson = new JsonArray();
        originJson.add(origin.getX());
        originJson.add(origin.getY());
        originJson.add(origin.getZ());
        root.add("origin", originJson);
        if (!schematic.dimension().isEmpty()) {
            root.addProperty("dimension", schematic.dimension());
        }

        JsonArray sizeJson = new JsonArray();
        for (int value : size) {
            sizeJson.add(value);
        }
        root.add("size", sizeJson);

        JsonArray paletteJson = new JsonArray();
        for (String state : palette) {
            paletteJson.add(state);
        }
        root.add("palette", paletteJson);
        root.add("layers", layersJson);

        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        // Пишем через временный файл: обрыв записи не должен уничтожить прошлое сохранение
        Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return path;
    }

    // ---- чтение ----

    /**
     * Загружает схему.
     *
     * @param origin куда положить минимальный угол; {@code null} означает «туда, где
     *               она и была размечена» — это обычный случай, ради него в файле и
     *               хранится абсолютный угол
     */
    public static TutorialSchematic load(String fileName, @org.jetbrains.annotations.Nullable BlockPos origin)
            throws IOException {
        Path path = pathFor(fileName);
        if (!Files.exists(path)) {
            throw new IOException("Файл не найден: " + path.getFileName());
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        return fromJson(root, origin);
    }

    public static TutorialSchematic fromJson(JsonObject root, @org.jetbrains.annotations.Nullable BlockPos origin)
            throws IOException {
        int format = root.has("format") ? root.get("format").getAsInt() : 1;
        if (format > FORMAT_VERSION) {
            throw new IOException("Файл сделан более новой версией мода (формат " + format + ")");
        }

        if (origin == null) {
            origin = readOrigin(root);
        }
        TutorialSchematic schematic = new TutorialSchematic(
                root.has("name") ? root.get("name").getAsString() : "Без названия");
        if (root.has("dimension")) schematic.setDimension(root.get("dimension").getAsString());
        if (root.has("author")) schematic.setAuthor(root.get("author").getAsString());
        if (root.has("created")) schematic.setCreated(root.get("created").getAsString());

        List<BlockState> palette = new ArrayList<>();
        if (root.has("palette")) {
            for (JsonElement element : root.getAsJsonArray("palette")) {
                palette.add(BlockData.parseState(element.getAsString()));
            }
        }

        if (!root.has("layers")) {
            return schematic;
        }

        for (JsonElement element : root.getAsJsonArray("layers")) {
            JsonObject layerJson = element.getAsJsonObject();

            int id = layerJson.has("id") ? layerJson.get("id").getAsInt() : schematic.layerCount() + 1;
            String name = layerJson.has("name") ? layerJson.get("name").getAsString() : "Слой " + id;

            BuildLayer layer = new BuildLayer(id, name);
            if (layerJson.has("color")) layer.setColor(layerJson.get("color").getAsInt());
            if (layerJson.has("startDelayTicks")) layer.setStartDelayTicks(layerJson.get("startDelayTicks").getAsInt());
            if (layerJson.has("endDelayTicks")) layer.setEndDelayTicks(layerJson.get("endDelayTicks").getAsInt());
            // Схемы, сохранённые до разделения на две задержки: прежняя пауза была «после слоя».
            if (layerJson.has("pauseAfterTicks")) layer.setEndDelayTicks(layerJson.get("pauseAfterTicks").getAsInt());
            if (layerJson.has("order")) layer.setOrder(OrderConfig.fromJson(layerJson.getAsJsonObject("order")));

            Map<String, String> nbtByPos = new HashMap<>();
            if (layerJson.has("nbt")) {
                for (Map.Entry<String, JsonElement> entry : layerJson.getAsJsonObject("nbt").entrySet()) {
                    nbtByPos.put(entry.getKey(), entry.getValue().getAsString());
                }
            }

            if (layerJson.has("blocks")) {
                JsonArray blocks = layerJson.getAsJsonArray("blocks");
                // плоский массив четвёрок: x, y, z, номер в палитре
                for (int i = 0; i + 3 < blocks.size(); i += 4) {
                    int rx = blocks.get(i).getAsInt();
                    int ry = blocks.get(i + 1).getAsInt();
                    int rz = blocks.get(i + 2).getAsInt();
                    int paletteId = blocks.get(i + 3).getAsInt();

                    BlockState state = paletteId >= 0 && paletteId < palette.size()
                            ? palette.get(paletteId)
                            : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();

                    CompoundTag nbt = BlockData.parseNbt(nbtByPos.get(rx + "," + ry + "," + rz));
                    BlockPos worldPos = origin.offset(rx, ry, rz);
                    layer.add(worldPos, new BlockData(state, nbt));
                }
            }

            if (layerJson.has("entities")) {
                for (JsonElement entityElement : layerJson.getAsJsonArray("entities")) {
                    JsonObject entityJson = entityElement.getAsJsonObject();
                    try {
                        CompoundTag nbt = NbtUtils.snbtToStructure(entityJson.get("nbt").getAsString());
                        layer.addEntity(new EntityData(
                                UUID.fromString(entityJson.get("id").getAsString()),
                                entityJson.get("type").getAsString(),
                                entityJson.get("x").getAsDouble() + origin.getX(),
                                entityJson.get("y").getAsDouble() + origin.getY(),
                                entityJson.get("z").getAsDouble() + origin.getZ(),
                                nbt));
                    } catch (Exception e) {
                        // одна сломанная декорация не должна ронять загрузку всей схемы
                        TutorialSchematicMod.LOGGER.warn("Не удалось прочитать декорацию: {}", e.getMessage());
                    }
                }
            }

            if (layerJson.has("seeds")) {
                JsonArray seedsJson = layerJson.getAsJsonArray("seeds");
                for (int i = 0; i + 2 < seedsJson.size(); i += 3) {
                    layer.addSeedRaw(origin.offset(
                            seedsJson.get(i).getAsInt(),
                            seedsJson.get(i + 1).getAsInt(),
                            seedsJson.get(i + 2).getAsInt()));
                }
            }

            schematic.addLayerRaw(layer);
        }
        return schematic;
    }

    /** Угол, в котором схему размечали. У файлов старого формата его нет — там начало координат. */
    private static BlockPos readOrigin(JsonObject root) {
        if (!root.has("origin")) {
            return BlockPos.ZERO;
        }
        JsonArray json = root.getAsJsonArray("origin");
        if (json.size() < 3) {
            return BlockPos.ZERO;
        }
        return new BlockPos(json.get(0).getAsInt(), json.get(1).getAsInt(), json.get(2).getAsInt());
    }

    /** Убирает из имени символы, недопустимые в имени файла. */
    public static String sanitize(String name) {
        String cleaned = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isEmpty() ? "schematic" : cleaned;
    }
}

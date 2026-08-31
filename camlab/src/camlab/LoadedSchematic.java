package camlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tutorialschematic.order.Pos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Схема из файла {@code .ltutorial} — без Minecraft.
 *
 * <p>Боевой {@code SchematicFiles} завязан на FabricLoader и мир игры, поэтому лаборатория
 * читает тот же JSON сама. Берётся только то, что нужно камерам и анимации: блоки с палитрой,
 * порядок ({@code order}), семена обхода и паузы. NBT, декорации и прочее игровое — мимо.
 */
public final class LoadedSchematic {

    /** Один слой: блоки уже в абсолютных координатах мира, как их видит камера. */
    public record Layer(String name, int color, int pauseAfterTicks, JsonObject orderJson,
                        List<Pos> blocks, List<Integer> paletteIndices, List<Pos> seeds) {
    }

    public final String name;
    public final int[] origin;
    public final List<String> palette;
    public final List<Layer> layers;

    private LoadedSchematic(String name, int[] origin, List<String> palette, List<Layer> layers) {
        this.name = name;
        this.origin = origin;
        this.palette = palette;
        this.layers = layers;
    }

    public static LoadedSchematic load(Path path) throws IOException {
        JsonObject root = JsonParser
                .parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();

        int[] origin = {0, 0, 0};
        if (root.has("origin")) {
            JsonArray originJson = root.getAsJsonArray("origin");
            for (int i = 0; i < 3; i++) {
                origin[i] = originJson.get(i).getAsInt();
            }
        }

        List<String> palette = new ArrayList<>();
        if (root.has("palette")) {
            for (var element : root.getAsJsonArray("palette")) {
                palette.add(element.getAsString());
            }
        }

        List<Layer> layers = new ArrayList<>();
        for (var layerElement : root.getAsJsonArray("layers")) {
            JsonObject layerJson = layerElement.getAsJsonObject();

            List<Pos> blocks = new ArrayList<>();
            List<Integer> paletteIndices = new ArrayList<>();
            JsonArray blocksJson = layerJson.getAsJsonArray("blocks");
            for (int i = 0; i + 3 < blocksJson.size(); i += 4) {
                blocks.add(new Pos(
                        blocksJson.get(i).getAsInt() + origin[0],
                        blocksJson.get(i + 1).getAsInt() + origin[1],
                        blocksJson.get(i + 2).getAsInt() + origin[2]));
                paletteIndices.add(blocksJson.get(i + 3).getAsInt());
            }

            List<Pos> seeds = new ArrayList<>();
            if (layerJson.has("seeds")) {
                JsonArray seedsJson = layerJson.getAsJsonArray("seeds");
                for (int i = 0; i + 2 < seedsJson.size(); i += 3) {
                    seeds.add(new Pos(
                            seedsJson.get(i).getAsInt() + origin[0],
                            seedsJson.get(i + 1).getAsInt() + origin[1],
                            seedsJson.get(i + 2).getAsInt() + origin[2]));
                }
            }

            layers.add(new Layer(
                    layerJson.has("name") ? layerJson.get("name").getAsString() : "Слой " + (layers.size() + 1),
                    layerJson.has("color") ? layerJson.get("color").getAsInt() : 0xFFFFFF,
                    layerJson.has("pauseAfterTicks") ? layerJson.get("pauseAfterTicks").getAsInt() : 0,
                    layerJson.has("order") ? layerJson.getAsJsonObject("order") : new JsonObject(),
                    blocks, paletteIndices, seeds));
        }

        String name = root.has("name") ? root.get("name").getAsString() : path.getFileName().toString();
        return new LoadedSchematic(name, origin, palette, layers);
    }
}

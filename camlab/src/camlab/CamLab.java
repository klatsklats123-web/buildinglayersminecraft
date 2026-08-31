package camlab;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Камерная лаборатория: локальный сервер с веб-просмотрщиком.
 *
 * <p>Отдельная от мода программа (см. camlab/README.md). Компилируется против его же
 * исходников, поэтому показывает ровно те алгоритмы, что поедут в игру: правка
 * {@code ShotPlanner} в моде видна здесь после обычного перезапуска.
 *
 * <p>Запуск: {@code run.bat} в папке camlab, дальше браузер на {@code http://localhost:8090}.
 */
public final class CamLab {

    private static final int PORT = 8090;
    private static final Gson GSON = new Gson();

    /** Где искать схемы: своя папка лаборатории плюс папки игры, если найдутся. */
    private static List<Path> schematicDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of("schematics"));
        Path modrinth = Path.of(System.getenv("APPDATA") == null ? "" : System.getenv("APPDATA"),
                "ModrinthApp", "profiles");
        if (Files.isDirectory(modrinth)) {
            try (var profiles = Files.list(modrinth)) {
                profiles.map(profile -> profile.resolve("tutorial-schematics"))
                        .filter(Files::isDirectory)
                        .forEach(dirs::add);
            } catch (IOException ignored) {
                // папки игры — необязательный бонус
            }
        }
        Path devRun = Path.of("..", "run", "tutorial-schematics");
        if (Files.isDirectory(devRun)) {
            dirs.add(devRun);
        }
        return dirs;
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/schematics", CamLab::handleSchematics);
        server.createContext("/api/styles", CamLab::handleStyles);
        server.createContext("/api/plan", CamLab::handlePlan);
        server.createContext("/", CamLab::handleStatic);
        server.start();
        System.out.println("Камерная лаборатория: http://localhost:" + PORT);
        System.out.println("Схемы ищутся в: " + schematicDirs());
    }

    // ---- API ----

    private static void handleSchematics(HttpExchange exchange) throws IOException {
        JsonArray result = new JsonArray();
        for (Map.Entry<String, Path> entry : findSchematics().entrySet()) {
            try {
                LoadedSchematic schematic = LoadedSchematic.load(entry.getValue());
                JsonObject item = new JsonObject();
                item.addProperty("file", entry.getKey());
                item.addProperty("name", schematic.name);
                JsonArray layers = new JsonArray();
                int blockTotal = 0;
                for (LoadedSchematic.Layer layer : schematic.layers) {
                    JsonObject layerJson = new JsonObject();
                    layerJson.addProperty("name", layer.name());
                    layerJson.addProperty("color", layer.color());
                    layerJson.addProperty("blocks", layer.blocks().size());
                    layerJson.addProperty("pauseAfterTicks", layer.pauseAfterTicks());
                    layerJson.add("order", layer.orderJson());
                    layers.add(layerJson);
                    blockTotal += layer.blocks().size();
                }
                item.add("layers", layers);
                item.addProperty("blocks", blockTotal);
                result.add(item);
            } catch (RuntimeException | IOException e) {
                JsonObject broken = new JsonObject();
                broken.addProperty("file", entry.getKey());
                broken.addProperty("error", String.valueOf(e.getMessage()));
                result.add(broken);
            }
        }
        respond(exchange, 200, "application/json", GSON.toJson(result));
    }

    /** Все доктрины съёмки мода — чтобы список дорожек в UI не расходился с кодом. */
    private static void handleStyles(HttpExchange exchange) throws IOException {
        JsonArray result = new JsonArray();
        for (com.tutorialschematic.camera.ShotStyle style : com.tutorialschematic.camera.ShotStyle.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", style.name());
            item.addProperty("displayName", style.displayName());
            item.addProperty("colour", style.trackColour());
            item.addProperty("exported", style.exported());
            item.addProperty("wholeBuild", style.wholeBuild());
            result.add(item);
        }
        respond(exchange, 200, "application/json", GSON.toJson(result));
    }

    private static void handlePlan(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            String file = request.get("file").getAsString();
            Path path = findSchematics().get(file);
            if (path == null) {
                respond(exchange, 404, "application/json", "{\"error\":\"Схема не найдена: " + file + "\"}");
                return;
            }
            LoadedSchematic schematic = LoadedSchematic.load(path);
            JsonObject settings = request.has("settings")
                    ? request.getAsJsonObject("settings") : new JsonObject();
            JsonObject plan = PlanService.plan(schematic, settings);
            respond(exchange, 200, "application/json", GSON.toJson(plan));
        } catch (RuntimeException | IOException e) {
            e.printStackTrace();
            JsonObject error = new JsonObject();
            error.addProperty("error", String.valueOf(e));
            respond(exchange, 500, "application/json", GSON.toJson(error));
        }
    }

    /** Все найденные схемы: подпись «файл (папка)» — путь. Имя остаётся стабильным ключом. */
    private static Map<String, Path> findSchematics() {
        Map<String, Path> found = new LinkedHashMap<>();
        for (Path dir : schematicDirs()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var files = Files.list(dir)) {
                files.filter(file -> file.getFileName().toString().endsWith(".ltutorial"))
                        .sorted()
                        .forEach(file -> {
                            String key = file.getFileName().toString();
                            if (found.containsKey(key)) {
                                key = key + " (" + dir.getFileName() + ")";
                            }
                            found.put(key, file);
                        });
            } catch (IOException ignored) {
                // недоступная папка — просто пропускаем
            }
        }
        return found;
    }

    // ---- статика ----

    private static void handleStatic(HttpExchange exchange) throws IOException {
        String raw = exchange.getRequestURI().getPath();
        String name = raw.equals("/") ? "index.html" : raw.substring(1);
        Path file = Path.of("web").resolve(name).normalize();
        // не выпускаем запросы за пределы web/
        if (!file.startsWith(Path.of("web")) || !Files.isRegularFile(file)) {
            respond(exchange, 404, "text/plain; charset=utf-8", "нет такого файла: " + name);
            return;
        }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private CamLab() {
    }
}

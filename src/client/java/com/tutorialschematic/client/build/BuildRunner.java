package com.tutorialschematic.client.build;

import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.flashback.BuildTicks;
import com.tutorialschematic.client.flashback.CameraExport;
import com.tutorialschematic.client.flashback.FlashbackBridge;
import com.tutorialschematic.client.flashback.RecordedBuild;
import com.tutorialschematic.order.StepPacer;
import com.tutorialschematic.schematic.BlockData;
import com.tutorialschematic.schematic.EntityData;
import com.tutorialschematic.schematic.BuildLayer;
import com.tutorialschematic.schematic.TutorialSchematic;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.TagValueInput;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Проигрывает постройку по слоям — то, что потом снимается на видео.
 *
 * <p>Рабочий цикл такой: постройка уже стоит в мире, её размечают по слоям, затем
 * {@link #clearAll} убирает её целиком, и {@link #start} возводит заново в заданном
 * порядке и темпе. Поэтому «откат на слой» — это не журнал изменений, а просто
 * «снести всё от этого слоя и дальше»: схема сама знает, какие блоки чьи.
 *
 * <p>Блоки ставятся на потоке встроенного сервера. На чужом сервере мод строить не
 * может — там нет прав менять мир напрямую, о чём честно сообщается.
 */
public final class BuildRunner {

    /**
     * Флаги установки блока: клиенту сообщаем, но соседей не трогаем и форму не
     * пересчитываем. Иначе половина двери или верх кровати «отвалится», пока вторая
     * половина ещё не поставлена.
     */
    private static final int PLACE_FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    public enum State {
        IDLE, RUNNING, PAUSED, FINISHED
    }

    private static final BuildRunner INSTANCE = new BuildRunner();

    public static BuildRunner get() {
        return INSTANCE;
    }

    private BuildRunner() {
    }

    private State state = State.IDLE;
    private TutorialSchematic schematic;

    private int layerIndex;
    /** Раскадровка текущего слоя: в каждом шаге блоки, встающие одновременно. */
    private List<List<BlockPos>> queue = List.of();
    private int queueCursor;

    /** Отсчёт такта постройки. Логика вынесена отдельно и покрыта тестами. */
    private final StepPacer pacer = new StepPacer();
    /**
     * Задержки считаются раздельно, и это важно для меток.
     *
     * <p>Между слоями идут подряд две паузы: доигрывает задержка после прошлого слоя, потом
     * начинается задержка перед следующим. Метка слоя ставится ровно на границе между ними —
     * то есть в начале <b>своей</b> задержки. Задержка перед слоем для того и нужна, чтобы
     * камера успела встать: если поставить метку у первого блока, камера появится ровно в
     * момент начала кладки, и склейка будет бросаться в глаза. Одним счётчиком эту границу
     * не поймать, поэтому их два.
     */
    private double endPauseLeft;
    private double startPauseLeft;
    /** Метка слоя ещё не поставлена: ждём начала задержки перед ним. */
    private boolean markPending;
    /** Постройку запустили со сносом, но сам снос ждёт первого тика — см. {@link #startFromLayer}. */
    private boolean clearPending;

    /** Измерение, в котором идёт постройка. Запоминается на старте, с клиентского потока. */
    private ResourceKey<Level> dimension;

    /** Множитель скорости: 2.0 — вдвое быстрее настроек слоя. */
    private double speedMultiplier = 1.0;
    private boolean playSounds = true;

    /**
     * На сколько блоков от слушателя источник звука ещё держат честным.
     *
     * <p>Игра глушит звук на шестнадцати блоках, помноженных на громкость. Берём с запасом,
     * чтобы у самой границы звук не оказался на нуле.
     */
    private static final double AUDIBLE_RANGE = 10.0;

    private long startedAtMillis;
    private int placedTotal;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(INSTANCE::tick);
    }

    // ---- состояние ----

    public State state() {
        return state;
    }

    public boolean isActive() {
        return state == State.RUNNING || state == State.PAUSED;
    }

    public int currentLayerIndex() {
        return layerIndex;
    }

    @Nullable
    public BuildLayer currentLayer() {
        return schematic == null ? null : schematic.layerAt(layerIndex);
    }

    public double speedMultiplier() {
        return speedMultiplier;
    }

    public void setSpeedMultiplier(double multiplier) {
        this.speedMultiplier = Math.max(0.1, Math.min(20.0, multiplier));
    }

    public boolean playSounds() {
        return playSounds;
    }

    public void setPlaySounds(boolean playSounds) {
        this.playSounds = playSounds;
    }

    public int placedTotal() {
        return placedTotal;
    }

    /** Прогресс текущего слоя от 0 до 1. */
    public double layerProgress() {
        return queue.isEmpty() ? 0 : (double) queueCursor / queue.size();
    }

    // ---- управление ----

    /** Запускает постройку с начала. Всё, что размечено, предварительно сносится. */
    public boolean start() {
        return startFromLayer(0, true);
    }

    /**
     * Запускает постройку с указанного слоя.
     *
     * @param index      номер слоя, с которого продолжаем
     * @param clearAhead снести слои начиная с этого (нужно при пересъёмке)
     */
    public boolean startFromLayer(int index, boolean clearAhead) {
        TutorialSchematic target = EditorState.get().schematic();
        if (target == null) {
            EditorState.error("Нет открытой схемы");
            return false;
        }
        if (target.layerCount() == 0) {
            EditorState.error("В схеме нет слоёв");
            return false;
        }
        ServerLevel startLevel = serverLevel();
        if (startLevel == null) {
            return false;
        }
        this.dimension = startLevel.dimension();
        int from = Math.max(0, Math.min(target.layerCount() - 1, index));

        this.schematic = target;
        // Снос не делаем здесь. Мы на клиентском потоке, и он уходит на сервер очередью
        // задач — по одной на блок. Заглушка первого слоя ставится уже на потоке сервера
        // и выполняется сразу, то есть влезает вперёд этой очереди: снос доходит следом и
        // стирает её, потому что позиции у них одни и те же. Поэтому сносим в самом тике,
        // где обе работы идут на одном потоке и по порядку.
        this.clearPending = clearAhead;

        this.layerIndex = from;
        this.placedTotal = 0;
        this.startedAtMillis = System.currentTimeMillis();
        // Замеры прошлой постройки к этой записи отношения не имеют.
        BuildTicks.reset();
        this.pacer.reset();
        this.endPauseLeft = 0;
        this.startPauseLeft = 0;
        loadLayerQueue();
        this.state = State.RUNNING;

        BuildLayer layer = currentLayer();
        EditorState.info("Постройка началась со слоя " + (from + 1) + "/" + target.layerCount()
                + " «" + (layer == null ? "?" : layer.name()) + "»");
        return true;
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            EditorState.info("Пауза");
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            state = State.RUNNING;
            EditorState.info("Продолжаем");
        }
    }

    public void togglePause() {
        if (state == State.RUNNING) {
            pause();
        } else if (state == State.PAUSED) {
            resume();
        }
    }

    public void stop() {
        if (state == State.IDLE) {
            return;
        }
        state = State.IDLE;
        queue = List.of();
        queueCursor = 0;
        // остановились до первого тика — сносить уже нечего и незачем
        clearPending = false;
        EditorState.info("Постройка остановлена");
    }

    /** Достраивает всё оставшееся мгновенно — когда нужен результат, а не запись. */
    public void finishInstantly() {
        if (schematic == null) {
            schematic = EditorState.get().schematic();
        }
        if (schematic == null) {
            EditorState.error("Нет открытой схемы");
            return;
        }
        ServerLevel level = serverLevel();
        if (level == null) {
            return;
        }
        int placed = 0;
        // начинаем с текущей позиции, чтобы не переставлять уже построенное
        for (int i = queueCursor; i < queue.size(); i++) {
            for (BlockPos pos : queue.get(i)) {
                if (placeBlock(level, currentLayer(), pos, false)) {
                    placed++;
                }
            }
        }
        placed += placeEntities(level, currentLayer());
        for (int i = layerIndex + 1; i < schematic.layerCount(); i++) {
            placed += buildLayerInstantly(level, schematic.layerAt(i));
        }
        state = State.FINISHED;
        queue = List.of();
        queueCursor = 0;
        EditorState.info("Достроено сразу: " + placed + " бл.");
    }

    /**
     * Перематывает к началу указанного слоя: предыдущие слои ставятся мгновенно,
     * этот и все следующие сносятся. Ровно то, что нужно для пересъёмки под другим ракурсом.
     */
    public boolean rewindTo(int index) {
        TutorialSchematic target = EditorState.get().schematic();
        if (target == null) {
            EditorState.error("Нет открытой схемы");
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null) {
            return false;
        }
        this.dimension = level.dimension();
        int to = Math.max(0, Math.min(target.layerCount() - 1, index));

        this.schematic = target;
        clearLayersFrom(to);

        int restored = 0;
        for (int i = 0; i < to; i++) {
            restored += buildLayerInstantly(level, target.layerAt(i));
        }

        this.layerIndex = to;
        this.queueCursor = 0;
        this.pacer.reset();
        this.endPauseLeft = 0;
        this.startPauseLeft = 0;
        loadLayerQueue();
        this.state = State.PAUSED;

        BuildLayer layer = target.layerAt(to);
        EditorState.info("Готово к слою " + (to + 1) + " «" + (layer == null ? "?" : layer.name())
                + "»: восстановлено " + restored + " бл. Нажмите продолжить, когда камера готова.");
        return true;
    }

    /** Убирает из мира все размеченные блоки — подготовка к съёмке. */
    public int clearAll() {
        TutorialSchematic target = EditorState.get().schematic();
        if (target == null) {
            EditorState.error("Нет открытой схемы");
            return 0;
        }
        this.schematic = target;
        int cleared = clearLayersFrom(0);
        this.state = State.IDLE;
        this.layerIndex = 0;
        this.queue = List.of();
        this.queueCursor = 0;
        EditorState.info("Снесено " + cleared + " бл. Постройка готова к запуску.");
        return cleared;
    }

    // ---- цикл ----

    /**
     * Один тик постройки. Вызывается с потока сервера, поэтому установка блоков идёт
     * без очереди: {@code server.execute} со своего же потока выполняет задачу сразу.
     */
    private void tick(MinecraftServer server) {
        if (state != State.RUNNING || schematic == null || dimension == null) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            state = State.IDLE;
            return;
        }

        // Снос перед постройкой — здесь, а не при запуске: тут мы на потоке сервера, и он
        // отрабатывает целиком до того, как слой начнёт что-либо ставить.
        if (clearPending) {
            clearPending = false;
            clearLayersFrom(level, layerIndex);
        }

        // Сначала доигрывает задержка после прошлого слоя — это ещё его время, и метка
        // следующего слоя сюда попадать не должна.
        if (endPauseLeft > 0) {
            endPauseLeft--;
            return;
        }

        BuildLayer layer = currentLayer();
        if (layer == null) {
            finish();
            return;
        }

        // Граница между задержками: отсюда начинается время нового слоя. Метку ставим здесь,
        // чтобы камера успела встать за свою задержку до первого блока — иначе она появится
        // ровно в момент начала кладки, и склейка будет бросаться в глаза. Прежде метку
        // ставили ещё раньше, до задержки прошлого слоя, и съёмка уезжала вперёд на обе паузы.
        if (markPending) {
            markPending = false;
            // Заглушка встаёт здесь же, на границе задержек: к этому моменту камера уже
            // на месте, и слой открывается сразу своей формой, а не пустотой.
            placePlaceholder(level, layer);
            if (FlashbackBridge.isRecording()) {
                CameraExport.markLayerStart(layerIndex, layer);
                // Тот же момент замеряем и себе: метка нужна глазу на таймлайне, а камерам
                // нужен тик, и брать его из метки — значит снова считать, а не мерить.
                BuildTicks.layerStarted(layerIndex);
            }
        }

        if (startPauseLeft > 0) {
            startPauseLeft--;
            return;
        }

        int steps = pacer.stepsThisTick(layer.order().ticksPerStep(), speedMultiplier);
        for (int i = 0; i < steps && queueCursor < queue.size(); i++) {
            placeStep(level, layer);
        }

        if (queueCursor >= queue.size()) {
            // остаток такта принадлежал этому слою — на следующий он не переносится
            pacer.dropRemainder();
            completeLayer(level, layer);
        }
    }

    /**
     * Один шаг раскадровки — все его блоки встают одновременно. Сколько их, решено
     * заранее: по счёту или по ширине фронта, смотря что выбрано у слоя.
     */
    private void placeStep(ServerLevel level, BuildLayer layer) {
        // Тик записи снимаем на самом шаге: по нему потом встанет граница ракурса, и это
        // единственный момент, когда время шага известно точно, а не выведено из соседей.
        BuildTicks.stepPlaced(layerIndex);
        boolean soundPlayed = false;
        for (BlockPos pos : queue.get(queueCursor++)) {
            // Звук у первого реально поставленного блока шага: на широком фронте иначе
            // получается треск, а привязка к номеру в списке оставляла бы шаг немым
            // целиком, если первый блок почему-то не встал.
            if (placeBlock(level, layer, pos, playSounds && !soundPlayed)) {
                placedTotal++;
                soundPlayed = true;
            }
        }
    }

    /**
     * Ставит блок-заглушку на всю площадь слоя разом.
     *
     * <p>Пока слой не построен, на его месте пустота, и у пола сквозь неё видно, что под
     * постройкой ничего нет. Заглушка закрывает площадь целиком, а дальше настоящие блоки
     * ложатся поверх неё по шагам — {@code setBlock} их просто перезаписывает, поэтому
     * убирать заглушку отдельно не нужно.
     *
     * <p>Незнакомая запись блока молча пропускается: подставлять свой блок нельзя, он
     * встал бы на весь слой.
     */
    private void placePlaceholder(ServerLevel level, BuildLayer layer) {
        BlockState placeholder = layer.placeholderState();
        if (placeholder == null || layer.isEmpty()) {
            return;
        }
        List<BlockPos> positions = new ArrayList<>(layer.blocks().keySet());
        MinecraftServer server = level.getServer();
        server.execute(() -> {
            for (BlockPos pos : positions) {
                level.setBlock(pos, placeholder, PLACE_FLAGS);
            }
        });
    }

    private void completeLayer(ServerLevel level, BuildLayer layer) {
        // декорации — в конце слоя: картина не висит на воздухе, ей нужна уже готовая стена
        int decorations = placeEntities(level, layer);

        EditorState.info("Слой " + (layerIndex + 1) + "/" + schematic.layerCount()
                + " «" + layer.name() + "» готов (" + layer.blockCount() + " бл."
                + (decorations > 0 ? ", декораций " + decorations : "") + ")");

        // Задержка после слоя — своя у слоя, ставится игроком. Делим на скорость, чтобы на
        // ускоренной постройке пауза не растягивала запись вчетверо.
        endPauseLeft = Math.round(layer.endDelayTicks() / speedMultiplier);
        layerIndex++;

        if (layerIndex >= schematic.layerCount()) {
            finish();
        } else {
            loadLayerQueue();
        }
    }

    private void finish() {
        if (FlashbackBridge.isRecording()) {
            CameraExport.markBuildFinished();
            BuildTicks.buildFinished();
            RecordedBuild.onBuildFinished();
        }
        state = State.FINISHED;
        queue = List.of();
        queueCursor = 0;
        double seconds = (System.currentTimeMillis() - startedAtMillis) / 1000.0;
        EditorState.info(String.format("Постройка завершена: %d бл. за %.1f с", placedTotal, seconds));
    }

    private void loadLayerQueue() {
        BuildLayer layer = currentLayer();
        queue = layer == null ? List.of() : layer.buildSteps();
        queueCursor = 0;
        // Метку не ставим здесь: она должна совпасть с первым блоком слоя, а до него ещё
        // задержка. Ставит её tick(), когда действительно начинает класть.
        markPending = layer != null;
        if (layer != null) {
            startPauseLeft = Math.round(layer.startDelayTicks() / speedMultiplier);
        }
    }

    // ---- работа с миром ----

    /**
     * Ставит один блок схемы. Возвращает {@code false}, если ставить было нечего.
     * Вся работа с миром уходит на поток сервера — трогать мир из клиентского тика нельзя.
     */
    private boolean placeBlock(ServerLevel level, @Nullable BuildLayer layer, BlockPos pos, boolean sound) {
        if (layer == null) {
            return false;
        }
        BlockData data = layer.get(pos);
        if (data == null) {
            return false;
        }
        MinecraftServer server = level.getServer();
        server.execute(() -> {
            level.setBlock(pos, data.state(), PLACE_FLAGS);
            applyNbt(level, pos, data.nbt());
            if (sound) {
                playPlaceSound(level, pos, data);
            }
        });
        return true;
    }

    /**
     * Звук установки блока, слышный с любого расстояния.
     *
     * <p>Громкость и высота — как у обычной установки рукой: иначе на записи слышно, что
     * блоки ставит мод, а не игрок.
     *
     * <p>Звук в мире затухает с расстоянием и дальше полутора десятков блоков не слышен
     * вовсе, а снимают постройку издалека — и стройка на записи выходила немой. Поэтому
     * источник, если он слишком далеко, подтягивается к слушателю по той же линии: слышно
     * его теперь всегда, а направление остаётся прежним, и вблизи ничего не меняется.
     */
    private void playPlaceSound(ServerLevel level, BlockPos pos, BlockData data) {
        var soundType = data.state().getSoundType();
        float volume = (soundType.getVolume() + 1.0f) / 2.0f;
        float pitch = soundType.getPitch() * 0.8f;

        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;

        Player listener = level.getNearestPlayer(x, y, z, -1, false);
        if (listener != null) {
            double dx = x - listener.getX();
            double dy = y - listener.getEyeY();
            double dz = z - listener.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double limit = AUDIBLE_RANGE * volume;
            if (distance > limit) {
                double scale = limit / distance;
                x = listener.getX() + dx * scale;
                y = listener.getEyeY() + dy * scale;
                z = listener.getZ() + dz * scale;
            }
        }
        level.playSound(null, x, y, z, soundType.getPlaceSound(), SoundSource.BLOCKS, volume, pitch);
    }

    /**
     * Убирает из мира перечисленные блоки. Нужно живому сносу разметки: размеченный блок
     * тут же исчезает, и под ним видно то, что размечается следующим.
     *
     * @return сколько блоков отправлено на снос
     */
    public int clearFromWorld(Collection<BlockPos> positions) {
        ServerLevel level = serverLevel();
        if (level == null) {
            return 0;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        int count = 0;
        for (BlockPos pos : positions) {
            BlockPos immutable = pos.immutable();
            level.getServer().execute(() -> level.setBlock(immutable, air, PLACE_FLAGS));
            count++;
        }
        return count;
    }

    /**
     * Ставит блоки слоя обратно в мир — отмена живого сноса.
     *
     * <p>Состояния берутся из самого слоя, поэтому вызывать надо <b>до</b> того, как блоки
     * из слоя удалили: после удаления восстанавливать уже нечего.
     */
    public int restoreToWorld(@Nullable BuildLayer layer, Collection<BlockPos> positions) {
        ServerLevel level = serverLevel();
        if (level == null || layer == null) {
            return 0;
        }
        int count = 0;
        for (BlockPos pos : positions) {
            if (placeBlock(level, layer, pos, false)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Включает или выключает живой снос, приводя мир в соответствие: при включении всё
     * уже размеченное убирается, при выключении возвращается. Обе стороны обратимы.
     *
     * <p>Подсветку режим намеренно <b>не</b> трогает: когда блоки убраны из мира, она
     * остаётся единственным способом понять, где слой вообще был. Гасится отдельно —
     * кнопкой «Подсветка» или клавишей {@code H}.
     */
    public void setAutoClear(boolean enabled) {
        EditorState state = EditorState.get();
        TutorialSchematic schematic = state.schematic();
        if (schematic == null) {
            EditorState.error("Нет открытой схемы");
            return;
        }
        if (state.autoClear() == enabled) {
            return;
        }
        int touched = 0;
        ServerLevel level = serverLevel();
        for (BuildLayer layer : schematic.layers()) {
            List<BlockPos> positions = new ArrayList<>(layer.blocks().keySet());
            touched += enabled ? clearFromWorld(positions) : restoreToWorld(layer, positions);
            if (level != null) {
                touched += enabled ? removeEntities(level, layer) : placeEntities(level, layer);
            }
        }
        state.setAutoClear(enabled);
        EditorState.info(enabled
                ? "Живой снос включён: убрано " + touched + " бл. Размеченное теперь исчезает сразу."
                : "Живой снос выключен: возвращено " + touched + " бл.");
    }

    /** Убирает из мира одну декорацию — живому сносу при разметке. */
    public void removeDecorationFromWorld(UUID id) {
        ServerLevel level = serverLevel();
        if (level == null) {
            return;
        }
        level.getServer().execute(() -> {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        });
    }

    /** Возвращает декорацию в мир — отмена живого сноса. */
    public void restoreDecorationToWorld(EntityData data) {
        ServerLevel level = serverLevel();
        if (level == null) {
            return;
        }
        level.getServer().execute(() -> spawnEntity(level, data));
    }

    private int buildLayerInstantly(ServerLevel level, @Nullable BuildLayer layer) {
        if (layer == null) {
            return 0;
        }
        int placed = 0;
        for (List<BlockPos> step : layer.buildSteps()) {
            for (BlockPos pos : step) {
                if (placeBlock(level, layer, pos, false)) {
                    placed++;
                }
            }
        }
        placed += placeEntities(level, layer);
        return placed;
    }

    /** Сносит блоки указанного слоя и всех следующих. */
    private int clearLayersFrom(int fromIndex) {
        ServerLevel level = serverLevel();
        return level == null ? 0 : clearLayersFrom(level, fromIndex);
    }

    /**
     * То же, но с уже добытым миром — чтобы можно было звать с потока сервера.
     *
     * <p>Оттуда {@code server.execute} выполняет задачу сразу, и снос успевает целиком до
     * того, как слой начнёт ставить блоки. С клиентского потока он, наоборот, копится в
     * очереди, и всё, что успело встать раньше, потом стирается.
     */
    private int clearLayersFrom(ServerLevel level, int fromIndex) {
        if (schematic == null) {
            return 0;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        int cleared = 0;
        for (int i = fromIndex; i < schematic.layerCount(); i++) {
            BuildLayer layer = schematic.layerAt(i);
            if (layer == null) {
                continue;
            }
            for (BlockPos pos : layer.blocks().keySet()) {
                BlockPos immutable = pos.immutable();
                level.getServer().execute(() -> level.setBlock(immutable, air, PLACE_FLAGS));
                cleared++;
            }
            BuildLayer target = layer;
            level.getServer().execute(() -> removeEntities(level, target));
        }
        return cleared;
    }

    /**
     * Ставит декорации слоя — картины, рамки, стенды.
     *
     * <p>Идут после блоков этого же слоя: картина не держится на воздухе, и если повесить
     * её раньше стены, она тут же отвалится.
     *
     * <p>UUID восстанавливается прежний. Так снос находит ровно то, что поставил мод, и
     * не задевает чужие декорации рядом.
     */
    private int placeEntities(ServerLevel level, @Nullable BuildLayer layer) {
        if (layer == null || layer.entityCount() == 0) {
            return 0;
        }
        int placed = 0;
        for (EntityData data : layer.entities().values()) {
            if (spawnEntity(level, data)) {
                placed++;
            }
        }
        return placed;
    }

    private boolean spawnEntity(ServerLevel level, EntityData data) {
        try {
            HolderLookup.Provider registries = level.registryAccess();
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, data.nbt());
            Entity entity = EntityType.loadEntityRecursive(
                    input, level, EntitySpawnReason.COMMAND, EntityProcessor.NOP);
            if (entity == null) {
                return false;
            }
            entity.snapTo(data.x(), data.y(), data.z(), entity.getYRot(), entity.getXRot());
            entity.setUUID(data.id());
            level.addFreshEntity(entity);
            return true;
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.warn("Не удалось поставить декорацию {}: {}",
                    data.typeId(), e.getMessage());
            return false;
        }
    }

    /** Убирает декорации слоя обратно — по тем же UUID, что и ставили. */
    private int removeEntities(ServerLevel level, @Nullable BuildLayer layer) {
        if (layer == null || layer.entityCount() == 0) {
            return 0;
        }
        int removed = 0;
        for (EntityData data : layer.entities().values()) {
            Entity entity = level.getEntity(data.id());
            if (entity != null) {
                entity.discard();
                removed++;
            }
        }
        return removed;
    }

    /** Восстанавливает содержимое сундуков, текст табличек и прочие данные блока. */
    private void applyNbt(ServerLevel level, BlockPos pos, @Nullable CompoundTag nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            return;
        }
        try {
            HolderLookup.Provider registries = level.registryAccess();
            entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, nbt));
            entity.setChanged();
        } catch (Exception e) {
            TutorialSchematicMod.LOGGER.warn("Не удалось восстановить данные блока на {}: {}", pos, e.getMessage());
        }
    }

    /**
     * Мир встроенного сервера. На чужом сервере мод менять блоки не может —
     * возвращаем {@code null} и объясняем почему.
     */
    @Nullable
    private ServerLevel serverLevel() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        if (!client.hasSingleplayerServer()) {
            EditorState.error("Постройка работает только в одиночном мире: на сервере мод не может ставить блоки");
            return null;
        }
        MinecraftServer server = client.getSingleplayerServer();
        return server == null ? null : server.getLevel(client.level.dimension());
    }
}

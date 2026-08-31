package com.tutorialschematic.client.flashback;

import com.tutorialschematic.TutorialSchematicMod;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Мост к моду Flashback — целиком через рефлексию.
 *
 * <p>Публичного API у Flashback нет, мы опираемся на публичные же поле и метод его класса
 * {@code Flashback}. Прямой зависимости при этом нет намеренно: мод должен работать и без
 * Flashback, и с той его версией, где всё переименуют. Любой сбой здесь означает «этой
 * возможности сейчас нет», а не падение.
 *
 * <p>Что используем:
 * <ul>
 *   <li>{@code Flashback.RECORDER} — публичное статическое поле, {@code null} вне записи;</li>
 *   <li>{@code Recorder.addMarker(ReplayMarker)} — метка кладётся на текущий тик записи;</li>
 *   <li>{@code Recorder.readyToWrite()} — слепок мира снят, можно начинать строить;</li>
 *   <li>{@code Flashback.startRecordingReplay()} — запуск записи.</li>
 * </ul>
 */
public final class FlashbackBridge {

    public static final String MOD_ID = "flashback";

    private static Boolean available;
    private static Class<?> flashbackClass;
    private static Field recorderField;
    private static Method addMarkerMethod;
    private static Method readyToWriteMethod;
    private static Method startRecordingMethod;
    private static Method finishRecordingMethod;
    private static Constructor<?> markerConstructor;
    private static Field writtenTicksField;
    private static boolean tickFieldMissing;
    private static boolean warned;

    private FlashbackBridge() {
    }

    /** Установлен ли Flashback и удалось ли найти всё, на что мы опираемся. */
    public static boolean isAvailable() {
        if (available != null) {
            return available;
        }
        available = false;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return false;
        }
        try {
            flashbackClass = Class.forName("com.moulberry.flashback.Flashback");
            recorderField = flashbackClass.getField("RECORDER");
            startRecordingMethod = flashbackClass.getMethod("startRecordingReplay");
            finishRecordingMethod = flashbackClass.getMethod("finishRecordingReplay");

            Class<?> recorderClass = Class.forName("com.moulberry.flashback.record.Recorder");
            Class<?> markerClass = Class.forName("com.moulberry.flashback.record.ReplayMarker");
            Class<?> positionClass = Class.forName("com.moulberry.flashback.record.ReplayMarker$MarkerPosition");

            addMarkerMethod = recorderClass.getMethod("addMarker", markerClass);
            readyToWriteMethod = recorderClass.getMethod("readyToWrite");
            markerConstructor = markerClass.getConstructor(int.class, positionClass, String.class);

            available = true;
        } catch (Throwable e) {
            warnOnce("Flashback найден, но его устройство изменилось — метки и камеры отключены: "
                    + e.getMessage());
        }
        return available;
    }

    /** Идёт ли запись прямо сейчас. */
    public static boolean isRecording() {
        return recorder() != null;
    }

    /**
     * Сколько тиков уже записано, либо -1, если узнать не удалось.
     *
     * <p>Своего счётчика Flashback наружу не отдаёт, а он нам нужен: по нему {@code addMarker}
     * и кладёт метки, то есть это ровно та шкала, в которой потом стоят камеры. Без него
     * время каждого ракурса приходится <i>вычислять</i> пропорцией между двумя метками, и
     * любая заминка в кладке — подгрузка чанков, рывок сервера — разводит расчёт с тем, что
     * происходило на самом деле.
     *
     * <p>Поле приватное, читаем рефлексией. Миксин был бы быстрее, но здесь это чтение
     * одного {@code int} раз в тик, а рефлексия ломается мягко: перестанет находить поле —
     * потеряем точное время и вернёмся к меткам, а не уроним игру. Остальной мост устроен
     * так же, и заводить ради одного поля миксины со своим конфигом смысла нет.
     */
    public static int recordingTick() {
        Object recorder = recorder();
        if (recorder == null || tickFieldMissing) {
            return -1;
        }
        try {
            if (writtenTicksField == null) {
                Field field = recorder.getClass().getDeclaredField("writtenTicks");
                field.setAccessible(true);
                writtenTicksField = field;
            }
            return writtenTicksField.getInt(recorder);
        } catch (Throwable e) {
            tickFieldMissing = true;
            TutorialSchematicMod.LOGGER.warn(
                    "Счётчик тиков Flashback не читается, время камер пойдёт по меткам: {}",
                    e.getMessage());
            return -1;
        }
    }

    /** Снят ли слепок мира. Пока не снят, строить рано — начало постройки в запись не попадёт. */
    public static boolean isReadyToWrite() {
        Object recorder = recorder();
        if (recorder == null) {
            return false;
        }
        try {
            return (boolean) readyToWriteMethod.invoke(recorder);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Запускает запись. Возвращает {@code false}, если не вышло или запись уже идёт. */
    public static boolean startRecording() {
        if (!isAvailable() || isRecording()) {
            return false;
        }
        try {
            startRecordingMethod.invoke(null);
            return true;
        } catch (Throwable e) {
            warnOnce("Не удалось запустить запись Flashback: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ставит метку на текущий тик записи. Flashback сам запомнит номер тика — именно по
     * нему мы потом и расставим камеры, читать его на ходу неоткуда.
     *
     * @param colour      цвет метки, обычно цвет слоя
     * @param description подпись, видна на таймлайне
     */
    public static boolean addMarker(int colour, String description) {
        Object recorder = recorder();
        if (recorder == null) {
            return false;
        }
        try {
            Object marker = markerConstructor.newInstance(colour, null, description);
            addMarkerMethod.invoke(recorder, marker);
            return true;
        } catch (Throwable e) {
            warnOnce("Не удалось поставить метку в записи: " + e.getMessage());
            return false;
        }
    }

    /**
     * Останавливает запись. Дальше Flashback сам решит, писать файл сразу или показать
     * экран сохранения — это его настройка Quicksave.
     */
    public static boolean finishRecording() {
        if (!isAvailable() || !isRecording()) {
            return false;
        }
        try {
            finishRecordingMethod.invoke(null);
            return true;
        } catch (Throwable e) {
            warnOnce("Не удалось остановить запись Flashback: " + e.getMessage());
            return false;
        }
    }

    public static Path dataDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve(MOD_ID);
    }

    public static Path replayFolder() {
        return dataDirectory().resolve("replays");
    }

    public static Path editorStateFolder() {
        return dataDirectory().resolve("editor_states");
    }

    private static Object recorder() {
        if (!isAvailable()) {
            return null;
        }
        try {
            return recorderField.get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            TutorialSchematicMod.LOGGER.warn(message);
        }
    }
}

package com.tutorialschematic.client.flashback;

import com.tutorialschematic.client.EditorState;
import com.tutorialschematic.client.build.BuildRunner;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Запуск постройки вместе с записью.
 *
 * <p>Между командой «пиши» и первым поставленным блоком обязана быть пауза: Flashback
 * сначала снимает слепок мира ({@code needsInitialSnapshot}), и пока он этого не сделал,
 * запись ещё ничего не принимает. Начнёшь строить раньше — начало постройки в реплей
 * не попадёт, а именно оно и есть самое ценное.
 *
 * <p>Поэтому запускаем запись, ждём готовности по клиентскому тику и только потом
 * говорим исполнителю строить.
 */
public final class RecordedBuild {

    /** Сколько тиков ждём готовности, прежде чем сдаться. Десять секунд с запасом. */
    private static final int TIMEOUT_TICKS = 200;

    private static boolean waiting;
    private static int waited;

    private RecordedBuild() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** Идёт ли сейчас ожидание слепка мира. */
    public static boolean isWaiting() {
        return waiting;
    }

    /**
     * Включает запись и ставит постройку в очередь на старт.
     *
     * @return {@code false}, если Flashback недоступен — тогда решать вызывающему
     */
    public static boolean startRecordingThenBuild() {
        if (!FlashbackBridge.isAvailable()) {
            EditorState.error("Flashback не найден — запустите постройку обычной кнопкой");
            return false;
        }
        if (!FlashbackBridge.isRecording() && !FlashbackBridge.startRecording()) {
            EditorState.error("Не удалось включить запись Flashback");
            return false;
        }
        waiting = true;
        waited = 0;
        EditorState.info("Запись включена, ждём слепок мира — постройка начнётся сама");
        return true;
    }

    public static void cancel() {
        waiting = false;
        waited = 0;
    }

    private static void tick() {
        if (!waiting) {
            return;
        }
        if (FlashbackBridge.isReadyToWrite()) {
            waiting = false;
            EditorState.info("Слепок снят, постройка пошла");
            BuildRunner.get().start();
            return;
        }
        if (++waited > TIMEOUT_TICKS) {
            waiting = false;
            EditorState.error("Flashback так и не начал запись — постройка не запущена");
        }
    }
}

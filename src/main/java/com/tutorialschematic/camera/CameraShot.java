package com.tutorialschematic.camera;

/**
 * Один ключевой кадр камеры: где стоит, куда смотрит и на каком тике записи.
 *
 * <p>{@code cut} отличает жёсткую склейку от плавного проезда: {@code true} — кадр
 * начинает новую фазу и к нему нельзя подъезжать сплайном (см. {@link ShotPlanner}), нужен
 * мгновенный монтажный переход; {@code false} — кадр продолжает движение внутри той же фазы.
 */
public record CameraShot(int tick, double x, double y, double z, float yaw, float pitch, boolean cut) {

    public CameraShot withCut(boolean cut) {
        return new CameraShot(tick, x, y, z, yaw, pitch, cut);
    }
}

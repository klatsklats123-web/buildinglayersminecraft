package com.tutorialschematic.camera;

/** Один ключевой кадр камеры: где стоит, куда смотрит и на каком тике записи. */
public record CameraShot(int tick, double x, double y, double z, float yaw, float pitch) {
}

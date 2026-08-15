package com.tutorialschematic.order;

/**
 * Позиция блока без привязки к классам Minecraft.
 *
 * <p>Нужна, чтобы сортировка и превью анимации оставались обычным кодом на Java:
 * их можно гонять тестами, не поднимая игру. Перевод в {@code BlockPos} и обратно
 * происходит только на границе с игровым кодом.
 */
public record Pos(int x, int y, int z) {

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}

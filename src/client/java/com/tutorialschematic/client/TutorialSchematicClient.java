package com.tutorialschematic.client;

import com.tutorialschematic.TutorialSchematicMod;
import com.tutorialschematic.client.build.BuildRunner;
import com.tutorialschematic.client.command.EditorCommands;
import com.tutorialschematic.client.render.EditorHud;
import com.tutorialschematic.client.render.WorldHighlightRenderer;
import com.tutorialschematic.client.selection.SelectionMode;
import com.tutorialschematic.client.selection.SelectionTool;
import com.tutorialschematic.client.selection.SelectionWand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Клиентская точка входа: подписки на ввод и рендер.
 */
public class TutorialSchematicClient implements ClientModInitializer {

    /**
     * Правая кнопка при удержании повторяется каждые несколько тиков. Повтор по тому же
     * блоку глушим, иначе в режиме двух точек одно нажатие успевало бы поставить угол
     * и тут же его закрыть.
     */
    private static final long USE_REPEAT_MS = 250;
    /** В режиме двух точек глушим повтор целиком, независимо от блока. */
    private static final long TWO_POINTS_REPEAT_MS = 300;

    /** Последний блок, обработанный при удержании левой кнопки — для «закраски» протяжкой. */
    private static BlockPos lastPaintPos;
    private static BlockPos lastUsePos;
    private static long lastUseTime;

    @Override
    public void onInitializeClient() {
        WorldHighlightRenderer.register();
        EditorHud.register();
        EditorCommands.register();
        BuildRunner.register();
        Keybinds.register();
        registerMarkupInput();
        registerDecorationInput();
        TutorialSchematicMod.LOGGER.info("Клиентская часть Tutorial Schematic готова");
    }

    /**
     * Перехват кликов инструментом разметки: правая кнопка добавляет, левая убирает.
     *
     * <p>Пока инструмент в руке и разметка включена, левый клик гасится <b>на каждом
     * тике</b>, а не только в момент нажатия: в креативе блок ломается мгновенно, и
     * одного пропущенного тика при удержании кнопки хватает, чтобы снести постройку,
     * которую как раз размечают.
     */
    private void registerMarkupInput() {
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            if (!shouldIntercept(client)) {
                lastPaintPos = null;
                return false;
            }
            BlockPos target = lookedAtBlock(client);
            if (target != null) {
                // clickCount — число свежих нажатий за тик: при простом удержании он равен
                // нулю. Событие приходит каждый тик, пока кнопка зажата, поэтому отличать
                // клик от удержания обязательно — иначе обработчик стреляет двадцать раз
                // в секунду и в режиме двух точек угол переставляется сам собой.
                boolean pressed = clickCount != 0;
                // при удержании обрабатываем каждый новый блок под прицелом — так лишнее
                // можно стирать протяжкой, как кистью
                boolean movedToNewBlock = !target.equals(lastPaintPos);
                boolean paintable = EditorState.get().mode() != SelectionMode.TWO_POINTS;

                if (pressed || (paintable && movedToNewBlock)) {
                    SelectionTool.handleClick(target, true);
                    lastPaintPos = target.immutable();
                }
            }
            return true;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // событие приходит и с серверной стороны встроенного сервера — там делать нечего
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !shouldIntercept(client)) {
                return InteractionResult.PASS;
            }
            BlockPos target = hitResult.getBlockPos();
            if (!isUseRepeat(target)) {
                SelectionTool.handleClick(target, false);
                lastUsePos = target.immutable();
                lastUseTime = System.currentTimeMillis();
            }
            // Именно FAIL, а не SUCCESS: Fabric отправляет на сервер пакет
            // взаимодействия, если результат consumesAction(). С SUCCESS клиент гасил
            // клик у себя, но пакет уходил — и люк открывал уже сервер.
            return InteractionResult.FAIL;
        });
    }

    /**
     * Клики по декорациям. Картина, рамка и стенд — сущности, и события по блокам до них
     * не доходят: луч попадает в сущность, а не в клетку за ней.
     *
     * <p>Оба обработчика возвращают {@code FAIL}: он не поглощает действие, поэтому Fabric
     * не отправляет пакет на сервер. С {@code SUCCESS} рамка успела бы провернуться, а
     * стенд — принять предмет, ещё до того, как мы их разметили.
     */
    private void registerDecorationInput() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !shouldIntercept(Minecraft.getInstance())) {
                return InteractionResult.PASS;
            }
            return SelectionTool.handleEntityClick(entity, false)
                    ? InteractionResult.FAIL
                    : InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!level.isClientSide() || !shouldIntercept(Minecraft.getInstance())) {
                return InteractionResult.PASS;
            }
            return SelectionTool.handleEntityClick(entity, true)
                    ? InteractionResult.FAIL
                    : InteractionResult.PASS;
        });
    }

    /** Отличает удержание правой кнопки от нового осмысленного клика. */
    private static boolean isUseRepeat(BlockPos target) {
        long elapsed = System.currentTimeMillis() - lastUseTime;
        if (EditorState.get().mode() == SelectionMode.TWO_POINTS) {
            return elapsed < TWO_POINTS_REPEAT_MS;
        }
        return target.equals(lastUsePos) && elapsed < USE_REPEAT_MS;
    }

    /** Реагируем на клики только с инструментом в руке при включённой разметке. */
    private static boolean shouldIntercept(Minecraft client) {
        EditorState state = EditorState.get();
        return state.markupEnabled()
                && state.hasSchematic()
                && client.level != null
                && SelectionWand.isHeld(client.player);
    }

    /** Блок под прицелом либо {@code null}, если игрок смотрит в пустоту. */
    @Nullable
    private static BlockPos lookedAtBlock(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }
}

package com.tutorialschematic.camera;

import com.tutorialschematic.order.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildTimelineTest {

    /** Работа едет слева направо: по столбику за шаг. */
    private static List<List<Pos>> movingFront(int length) {
        List<List<Pos>> steps = new ArrayList<>();
        for (int x = 0; x < length; x++) {
            steps.add(List.of(new Pos(x, 0, 0), new Pos(x, 1, 0)));
        }
        return steps;
    }

    @Test
    void замерыИдутВследЗаФронтом() {
        List<BuildTimeline.FrontSample> samples = BuildTimeline.sample(movingFront(40), 0, 600);

        assertTrue(samples.size() >= 3, "на длинный слой нужно несколько замеров");
        for (int i = 1; i < samples.size(); i++) {
            assertTrue(samples.get(i).center()[0] > samples.get(i - 1).center()[0],
                    "центр работы должен ехать вправо");
            assertTrue(samples.get(i).tick() > samples.get(i - 1).tick(),
                    "и время должно идти вперёд");
        }
    }

    @Test
    void замерыУкладываютсяВГраницыСлоя() {
        int start = 1000, end = 1600;
        for (BuildTimeline.FrontSample sample : BuildTimeline.sample(movingFront(40), start, end)) {
            assertTrue(sample.tick() >= start, "замер не раньше начала слоя");
            assertTrue(sample.tick() <= end, "и не позже конца");
        }
    }

    @Test
    void короткийСлойНеДробитсяВПыль() {
        // на слой в пару секунд хватает пары замеров, иначе камера начнёт дёргаться
        List<BuildTimeline.FrontSample> samples = BuildTimeline.sample(movingFront(40), 0, 40);
        assertTrue(samples.size() <= 3, "слишком много замеров на короткий слой: " + samples.size());
    }

    @Test
    void замеровНеБольшеЧемШагов() {
        List<BuildTimeline.FrontSample> samples = BuildTimeline.sample(movingFront(2), 0, 2000);
        assertTrue(samples.size() <= 2, "замеров больше, чем самих шагов");
    }

    @Test
    void радиусЗамераМеньшеРадиусаВсегоСлоя() {
        // ближний план кадрируется по фронту, и он обязан быть теснее слоя целиком
        List<List<Pos>> steps = movingFront(40);
        List<Pos> all = new ArrayList<>();
        steps.forEach(all::addAll);

        List<BuildTimeline.FrontSample> samples = BuildTimeline.sample(steps, 0, 600);
        double layerRadius = ShotPlanner.radiusOf(all, ShotPlanner.centerOf(all));

        for (BuildTimeline.FrontSample sample : samples) {
            assertTrue(sample.radius() < layerRadius, "фронт теснее слоя");
        }
    }

    @Test
    void пустойСлойИНулеваяДлительностьНеПадают() {
        assertEquals(0, BuildTimeline.sample(List.of(), 0, 100).size());
        assertEquals(0, BuildTimeline.sample(movingFront(10), 100, 100).size());
    }
}

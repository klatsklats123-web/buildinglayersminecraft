// Камерная лаборатория: просмотрщик плана камер поверх three.js.
// Сервер отдаёт план (блоки в порядке появления, тики шагов, кадры дорожек, метрики),
// здесь он проигрывается: постройка растёт по тикам, камера едет по своим кадрам.

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

// ---------- состояние ----------

const state = {
  schematics: [],       // список с /api/schematics
  styles: [],           // список с /api/styles
  plan: null,           // последний ответ /api/plan
  layerEdits: {},       // файл -> {индекс слоя -> order JSON} — правки анимации
  tick: 0,
  maxTick: 0,
  playing: false,
  viewMode: 'orbit',    // orbit | camera
  activeTrack: 0,
};

const $ = id => document.getElementById(id);

// ---------- three.js ----------

const canvas = $('canvas');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
renderer.setPixelRatio(window.devicePixelRatio);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x1a1d22);

const orbitCam = new THREE.PerspectiveCamera(60, 1, 0.1, 4000);
const controls = new OrbitControls(orbitCam, canvas);
controls.enableDamping = true;
// колёсико по умолчанию слишком резкое: шаг меньше, зум — к курсору,
// а пределы дистанции ставятся под размер постройки в buildWorld
controls.zoomSpeed = 0.4;
controls.zoomToCursor = true;

// камера дорожки: ею рендерится вид «через камеру», с неё же строится пирамидка в орбите
const shotCam = new THREE.PerspectiveCamera(70, 9 / 16, 0.1, 4000);
const indicatorCam = new THREE.PerspectiveCamera(70, 9 / 16, 0.3, 7);
const camHelper = new THREE.CameraHelper(indicatorCam);
camHelper.visible = false;
scene.add(camHelper);

scene.add(new THREE.AmbientLight(0xffffff, 0.75));
const sun = new THREE.DirectionalLight(0xffffff, 1.6);
sun.position.set(0.6, 1, 0.35);
scene.add(sun);

let blockMesh = null;      // InstancedMesh, экземпляры в порядке появления
let baseColors = null;     // цвета палитры до подсветки брака
let worldGroup = new THREE.Group();  // всё, что пересоздаётся на новый план
scene.add(worldGroup);

const letterboxes = [0, 1, 2, 3].map(() => {
  const div = document.createElement('div');
  div.className = 'letterbox';
  $('viewport').appendChild(div);
  return div;
});
$('letterbox-left').remove();
$('letterbox-right').remove();

// ---------- загрузка списков ----------

async function boot() {
  const [styles, schematics] = await Promise.all([
    fetch('/api/styles').then(r => r.json()),
    fetch('/api/schematics').then(r => r.json()),
  ]);
  state.styles = styles;
  state.schematics = schematics.filter(s => !s.error);

  const select = $('schematic');
  for (const item of state.schematics) {
    const option = document.createElement('option');
    option.value = item.file;
    option.textContent = `${item.file} — ${item.name} (${item.blocks} бл.)`;
    select.appendChild(option);
  }
  select.onchange = () => buildLayerUI();

  const tracksDiv = $('tracks');
  for (const style of styles) {
    const row = document.createElement('label');
    row.className = 'row';
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = style.exported;
    box.dataset.style = style.name;
    const swatch = document.createElement('span');
    swatch.className = 'swatch';
    swatch.style.background = '#' + style.colour.toString(16).padStart(6, '0');
    row.append(box, swatch, document.createTextNode(style.displayName));
    tracksDiv.appendChild(row);
  }

  buildLayerUI();
  $('status').textContent = state.schematics.length
    ? 'Выберите схему и нажмите «Рассчитать»'
    : 'Схем не найдено — положите .ltutorial в camlab/schematics';
}

function currentSchematic() {
  return state.schematics.find(s => s.file === $('schematic').value) || state.schematics[0];
}

// ---------- настройки анимации по слоям ----------

function buildLayerUI() {
  const holder = $('layers');
  holder.innerHTML = '';
  const item = currentSchematic();
  if (!item) return;
  $('schematic-info').textContent = `${item.layers.length} слоёв`;

  const edits = state.layerEdits[item.file] ??= {};
  item.layers.forEach((layer, index) => {
    const order = edits[index] ??= structuredClone(layer.order ?? {});
    order.keys ??= [{ formula: 'y', descending: false }];
    order.batchSize ??= 1;
    order.ticksPerStep ??= 2;
    order.frontStep ??= false;
    order.seed ??= 12345;

    const box = document.createElement('div');
    box.className = 'layer';
    const head = document.createElement('div');
    head.className = 'layer-head';
    const swatch = document.createElement('span');
    swatch.className = 'swatch';
    swatch.style.background = '#' + (layer.color >>> 0 & 0xFFFFFF).toString(16).padStart(6, '0');
    const count = document.createElement('span');
    count.className = 'count';
    count.textContent = layer.blocks + ' бл.';
    head.append(swatch, document.createTextNode(layer.name), count);
    head.onclick = () => box.classList.toggle('open');

    const body = document.createElement('div');
    body.className = 'layer-body';

    const keysDiv = document.createElement('div');
    const renderKeys = () => {
      keysDiv.innerHTML = '';
      order.keys.forEach((key, ki) => {
        const row = document.createElement('div');
        row.className = 'key-row';
        const input = document.createElement('input');
        input.type = 'text';
        input.value = key.formula ?? 'y';
        input.oninput = () => { key.formula = input.value; };
        const desc = document.createElement('button');
        desc.textContent = key.descending ? '↓' : '↑';
        desc.title = 'направление сортировки';
        desc.onclick = () => { key.descending = !key.descending; desc.textContent = key.descending ? '↓' : '↑'; };
        const del = document.createElement('button');
        del.textContent = '×';
        del.onclick = () => { if (order.keys.length > 1) { order.keys.splice(ki, 1); renderKeys(); } };
        row.append(input, desc, del);
        keysDiv.appendChild(row);
      });
      if (order.keys.length < 4) {
        const add = document.createElement('button');
        add.textContent = '+ уровень сортировки';
        add.style.marginTop = '3px';
        add.onclick = () => { order.keys.push({ formula: 'y', descending: false }); renderKeys(); };
        keysDiv.appendChild(add);
      }
    };
    renderKeys();

    const mini = document.createElement('div');
    mini.className = 'mini';
    mini.append(
      numberField('За шаг', order.batchSize, 1, 512, v => order.batchSize = v),
      numberField('Тиков/шаг', order.ticksPerStep, 0, 200, v => order.ticksPerStep = v),
    );
    const front = document.createElement('label');
    front.className = 'row';
    const frontBox = document.createElement('input');
    frontBox.type = 'checkbox';
    frontBox.checked = order.frontStep;
    frontBox.onchange = () => order.frontStep = frontBox.checked;
    front.append(frontBox, document.createTextNode('Шаг по фронту (по равным значениям формулы)'));

    body.append(keysDiv, mini, front);
    box.append(head, body);
    holder.appendChild(box);
  });
}

function numberField(title, value, min, max, onChange) {
  const label = document.createElement('label');
  label.textContent = title;
  const input = document.createElement('input');
  input.type = 'number';
  input.value = value;
  input.min = min;
  input.max = max;
  input.onchange = () => onChange(parseInt(input.value) || 0);
  label.appendChild(input);
  return label;
}

// ---------- расчёт плана ----------

async function doPlan() {
  const item = currentSchematic();
  if (!item) return;
  const tracks = [...document.querySelectorAll('#tracks input:checked')].map(b => b.dataset.style);
  const settings = {
    fov: parseFloat($('fov').value) || 70,
    aspect: parseFloat($('aspect').value),
    envelope: $('envelope').checked,
    tracks,
    layers: state.layerEdits[item.file] ?? {},
  };
  $('plan').disabled = true;
  $('status').textContent = 'Считаем…';
  try {
    const response = await fetch('/api/plan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ file: item.file, settings }),
    });
    const plan = await response.json();
    if (plan.error) throw new Error(plan.error);
    state.plan = plan;
    buildWorld(plan);
    $('status').textContent = (plan.warnings ?? []).join('; ') || 'Готово';
  } catch (e) {
    $('status').textContent = 'Ошибка: ' + e.message;
  } finally {
    $('plan').disabled = false;
  }
}

// ---------- сборка мира из плана ----------

function paletteColor(stateString) {
  // устойчивый пастельный цвет из имени блока — карту текстур мы не тащим
  let hash = 0;
  for (const ch of stateString) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  const color = new THREE.Color();
  color.setHSL((hash % 360) / 360, 0.42, 0.58);
  return color;
}

function buildWorld(plan) {
  scene.remove(worldGroup);
  worldGroup.traverse(o => { o.geometry?.dispose(); o.material?.dispose?.(); });
  worldGroup = new THREE.Group();
  scene.add(worldGroup);

  const positions = plan.blockPositions;
  const total = positions.length / 3;
  const box = plan.bbox;
  const center = new THREE.Vector3((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);

  // блоки: экземпляры лежат в порядке появления, поэтому «сколько уже стоит» = mesh.count
  const geometry = new THREE.BoxGeometry(1, 1, 1);
  const material = new THREE.MeshLambertMaterial();
  blockMesh = new THREE.InstancedMesh(geometry, material, total);
  const matrix = new THREE.Matrix4();
  baseColors = [];
  const paletteColors = plan.palette.map(paletteColor);
  for (let i = 0; i < total; i++) {
    matrix.setPosition(positions[i * 3] + 0.5, positions[i * 3 + 1] + 0.5, positions[i * 3 + 2] + 0.5);
    blockMesh.setMatrixAt(i, matrix);
    const color = paletteColors[plan.blockPalette[i]] ?? new THREE.Color(0x888888);
    baseColors.push(color);
    blockMesh.setColorAt(i, color);
  }
  blockMesh.count = 0;
  // Отсечение по видимости у InstancedMesh считает габарит по одному кубику в начале
  // координат, а не по экземплярам (да ещё и при count=0) — под частью углов вся
  // постройка «исчезала» целиком. Дешевле не отсекать вовсе: меш у нас один.
  blockMesh.frustumCulled = false;
  worldGroup.add(blockMesh);

  // габарит и земля
  const bboxHelper = new THREE.Box3Helper(
    new THREE.Box3(new THREE.Vector3(box[0], box[1], box[2]), new THREE.Vector3(box[3], box[4], box[5])),
    0x3a4150);
  worldGroup.add(bboxHelper);
  const spread = Math.max(box[3] - box[0], box[5] - box[2]) * 4;
  const grid = new THREE.GridHelper(spread, Math.round(spread), 0x30343c, 0x23262c);
  grid.position.set(center.x, box[1], center.z);
  worldGroup.add(grid);

  // траектории дорожек
  for (const [ti, track] of plan.tracks.entries()) {
    const points = track.shots.map(s => new THREE.Vector3(s.x, s.y, s.z));
    if (!points.length) continue;
    const colour = new THREE.Color(track.colour);
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(points),
      new THREE.LineBasicMaterial({ color: colour, transparent: true }));
    line.frustumCulled = false;
    line.userData.trackIndex = ti;
    worldGroup.add(line);
    const dots = new THREE.Points(
      new THREE.BufferGeometry().setFromPoints(points),
      new THREE.PointsMaterial({ color: colour, size: 4, sizeAttenuation: false, transparent: true }));
    dots.userData.trackIndex = ti;
    worldGroup.add(dots);
    const cuts = track.shots.filter(s => s.cut).map(s => new THREE.Vector3(s.x, s.y, s.z));
    if (cuts.length) {
      const cutDots = new THREE.Points(
        new THREE.BufferGeometry().setFromPoints(cuts),
        new THREE.PointsMaterial({ color: 0xffffff, size: 7, sizeAttenuation: false, transparent: true }));
      cutDots.userData.trackIndex = ti;
      worldGroup.add(cutDots);
    }
  }

  // камера обзора — на постройку
  const size = Math.max(box[3] - box[0], box[4] - box[1], box[5] - box[2]);
  controls.target.copy(center);
  orbitCam.position.set(center.x + size * 1.6, center.y + size * 1.1, center.z + size * 1.6);
  // без пределов один щелчок колёсика у самой постройки пролетает её насквозь
  controls.minDistance = size * 0.08;
  controls.maxDistance = size * 10;
  controls.update();

  // таймлайн
  state.maxTick = plan.stepTicks[plan.stepTicks.length - 1];
  state.tick = 0;
  $('scrub').max = state.maxTick;
  $('scrub').value = 0;

  // выбор активной дорожки
  const select = $('active-track');
  select.innerHTML = '';
  plan.tracks.forEach((track, index) => {
    const option = document.createElement('option');
    option.value = index;
    option.textContent = track.displayName;
    select.appendChild(option);
  });
  state.activeTrack = 0;

  renderMetrics(plan);
  renderStrips(plan);
  applyHighlight();
  camHelper.visible = true;
}

// ---------- метрики и полосы ----------

function renderMetrics(plan) {
  const body = $('metrics').tBodies[0];
  body.innerHTML = '';
  const head = document.createElement('tr');
  head.innerHTML = '<td></td><td class="num">заслон</td><td class="num">за кадром</td><td class="num">внутри дома</td>';
  body.appendChild(head);
  for (const track of plan.tracks) {
    const m = track.metrics;
    if (m.blocks === undefined) continue;
    const row = document.createElement('tr');
    const inside = m.insideHousePct ?? 0;
    row.innerHTML = `<td>${track.displayName}</td>`
      + `<td class="num${m.occludedPct > 15 ? ' bad' : ''}">${m.occludedPct}%</td>`
      + `<td class="num${m.outOfFramePct > 5 ? ' bad' : ''}">${m.outOfFramePct}%</td>`
      + `<td class="num ${inside > 5 ? 'bad' : 'good'}">${m.insideHouse}/${m.shots}</td>`;
    body.appendChild(row);
  }
}

function renderStrips(plan) {
  const scenes = $('scene-strip');
  const layers = $('layer-strip');
  scenes.innerHTML = '';
  layers.innerHTML = '';
  const max = state.maxTick || 1;

  const track = plan.tracks[state.activeTrack];
  if (track) {
    const colour = '#' + track.colour.toString(16).padStart(6, '0');
    for (const scene of track.scenes) {
      const seg = document.createElement('div');
      seg.className = 'strip-seg' + (scene.interior ? ' interior' : '');
      seg.style.left = (100 * scene.startTick / max) + '%';
      seg.style.width = Math.max(0.4, 100 * (scene.endTick - scene.startTick) / max) + '%';
      seg.style.background = scene.interior ? '#c9762f' : colour;
      seg.title = `${scene.shape}${scene.interior ? ' · интерьер' : ''} · ${scene.blocks} бл. · азимут ${scene.azimuth}°`;
      scenes.appendChild(seg);
    }
  }
  for (const layer of plan.layers) {
    const seg = document.createElement('div');
    seg.className = 'strip-seg';
    seg.style.left = (100 * layer.startTick / max) + '%';
    seg.style.width = Math.max(0.4, 100 * (layer.endTick - layer.startTick) / max) + '%';
    seg.style.background = '#' + (layer.color >>> 0 & 0xFFFFFF).toString(16).padStart(6, '0');
    seg.style.opacity = 0.65;
    seg.title = layer.name;
    layers.appendChild(seg);
  }
}

function applyHighlight() {
  if (!blockMesh || !state.plan) return;
  const track = state.plan.tracks[state.activeTrack];
  const bad = new Set($('highlight').checked && track ? track.metrics.occludedBlocks ?? [] : []);
  const red = new THREE.Color(0xe04040);
  for (let i = 0; i < baseColors.length; i++) {
    blockMesh.setColorAt(i, bad.has(i) ? red : baseColors[i]);
  }
  blockMesh.instanceColor.needsUpdate = true;
}

// ---------- проигрывание ----------

function placedCountAt(tick) {
  const { stepTicks, stepStarts } = state.plan;
  // блоки шага i считаются вставшими, когда шаг завершился: stepTicks[i+1] <= tick
  let low = 0, high = stepTicks.length - 1;
  while (low < high) {
    const mid = (low + high + 1) >> 1;
    if (stepTicks[mid] <= tick) low = mid; else high = mid - 1;
  }
  return stepStarts[Math.min(low, stepStarts.length - 1)];
}

function shortestTurn(degrees) {
  return ((degrees % 360) + 540) % 360 - 180;
}

/** Положение камеры дорожки на тике: HOLD держит кадр, плавный переход интерполируется. */
function cameraPoseAt(track, tick) {
  const shots = track.shots;
  if (!shots.length) return null;
  let index = 0;
  while (index + 1 < shots.length && shots[index + 1].tick <= tick) index++;
  const current = shots[index];
  const next = shots[index + 1];
  if (!next || next.cut || next.tick <= current.tick || tick <= current.tick) {
    return current;
  }
  const t = Math.min(1, (tick - current.tick) / (next.tick - current.tick));
  return {
    x: current.x + (next.x - current.x) * t,
    y: current.y + (next.y - current.y) * t,
    z: current.z + (next.z - current.z) * t,
    yaw: current.yaw + shortestTurn(next.yaw - current.yaw) * t,
    pitch: current.pitch + (next.pitch - current.pitch) * t,
  };
}

function applyPose(camera, pose) {
  camera.position.set(pose.x, pose.y, pose.z);
  const yaw = pose.yaw * Math.PI / 180;
  const pitch = pose.pitch * Math.PI / 180;
  const dir = new THREE.Vector3(
    -Math.sin(yaw) * Math.cos(pitch),
    -Math.sin(pitch),
    Math.cos(yaw) * Math.cos(pitch));
  camera.lookAt(camera.position.clone().add(dir));
}

function updateShotInfo(track, tick) {
  const scene = track?.scenes.find(s => tick >= s.startTick && tick < s.endTick);
  $('shot-info').textContent = scene
    ? `${scene.shape}${scene.interior ? ' · ИНТЕРЬЕР (изнутри)' : ''} · азимут ${scene.azimuth}°`
    : '';
}

// ---------- рендер-цикл ----------

let lastTime = performance.now();

function layoutCameraView() {
  const viewport = $('viewport');
  const W = viewport.clientWidth, H = viewport.clientHeight;
  const aspect = parseFloat($('aspect').value);
  let width = W, height = H, left = 0, top = 0;
  if (W / H > aspect) {
    width = Math.round(H * aspect);
    left = Math.round((W - width) / 2);
  } else {
    height = Math.round(W / aspect);
    top = Math.round((H - height) / 2);
  }
  return { W, H, width, height, left, top };
}

function updateOverlays(rect) {
  const showCamera = state.viewMode === 'camera' && state.plan;
  const [leftDiv, rightDiv, topDiv, bottomDiv] = letterboxes;
  for (const div of letterboxes) div.style.display = showCamera ? 'block' : 'none';
  $('safezone').style.display = showCamera ? 'block' : 'none';
  if (!showCamera) return;
  Object.assign(leftDiv.style, { left: 0, top: 0, bottom: 0, width: rect.left + 'px', height: 'auto', right: 'auto' });
  Object.assign(rightDiv.style, { right: 0, top: 0, bottom: 0, width: (rect.W - rect.left - rect.width) + 'px', left: 'auto', height: 'auto' });
  Object.assign(topDiv.style, { left: rect.left + 'px', top: 0, width: rect.width + 'px', height: rect.top + 'px', bottom: 'auto', right: 'auto' });
  Object.assign(bottomDiv.style, { left: rect.left + 'px', bottom: 0, width: rect.width + 'px', height: (rect.H - rect.top - rect.height) + 'px', top: 'auto', right: 'auto' });
  const safe = $('safezone');
  safe.style.left = rect.left + rect.width * 0.1 + 'px';
  safe.style.top = rect.top + rect.height * 0.1 + 'px';
  safe.style.width = rect.width * 0.8 + 'px';
  safe.style.height = rect.height * 0.8 + 'px';
}

function animate(now) {
  requestAnimationFrame(animate);
  const dt = Math.min(0.1, (now - lastTime) / 1000);
  lastTime = now;

  const viewport = $('viewport');
  const W = viewport.clientWidth, H = viewport.clientHeight;
  if (canvas.width !== Math.round(W * devicePixelRatio) || canvas.height !== Math.round(H * devicePixelRatio)) {
    renderer.setSize(W, H, false);
  }

  if (state.plan) {
    if (state.playing) {
      state.tick += dt * 20 * parseFloat($('speed').value);
      if (state.tick >= state.maxTick) {
        state.tick = state.maxTick;
        setPlaying(false);
      }
      $('scrub').value = state.tick;
    }
    $('tick-label').textContent = `тик ${Math.floor(state.tick)} / ${state.maxTick}`;
    blockMesh.count = placedCountAt(state.tick);

    const track = state.plan.tracks[state.activeTrack];
    const pose = track && cameraPoseAt(track, state.tick);
    if (pose) {
      applyPose(shotCam, pose);
      applyPose(indicatorCam, pose);
      indicatorCam.fov = parseFloat($('fov').value) || 70;
      indicatorCam.aspect = parseFloat($('aspect').value);
      indicatorCam.updateProjectionMatrix();
      camHelper.update();
    }
    updateShotInfo(track, state.tick);

    // чужие дорожки полупрозрачны, активная — в полную силу
    worldGroup.traverse(o => {
      if (o.userData.trackIndex !== undefined && o.material) {
        o.material.opacity = o.userData.trackIndex === state.activeTrack ? 1 : 0.18;
      }
    });
  }

  const rect = layoutCameraView();
  updateOverlays(rect);

  if (state.viewMode === 'camera' && state.plan) {
    renderer.setScissorTest(false);
    renderer.setViewport(0, 0, W, H);
    renderer.clear();
    camHelper.visible = false;
    shotCam.fov = parseFloat($('fov').value) || 70;
    shotCam.aspect = parseFloat($('aspect').value);
    shotCam.updateProjectionMatrix();
    renderer.setScissorTest(true);
    // у three.js начало координат вьюпорта внизу слева
    renderer.setViewport(rect.left, rect.H - rect.top - rect.height, rect.width, rect.height);
    renderer.setScissor(rect.left, rect.H - rect.top - rect.height, rect.width, rect.height);
    renderer.render(scene, shotCam);
    renderer.setScissorTest(false);
  } else {
    camHelper.visible = !!state.plan;
    controls.update();
    orbitCam.aspect = W / H;
    orbitCam.updateProjectionMatrix();
    renderer.setViewport(0, 0, W, H);
    renderer.render(scene, orbitCam);
  }
}

// ---------- управление ----------

function setPlaying(playing) {
  state.playing = playing;
  $('play').textContent = playing ? '⏸' : '▶';
}

function setViewMode(mode) {
  state.viewMode = mode;
  $('view-orbit').classList.toggle('active', mode === 'orbit');
  $('view-camera').classList.toggle('active', mode === 'camera');
}

$('plan').onclick = doPlan;
$('play').onclick = () => {
  if (!state.plan) return;
  if (!state.playing && state.tick >= state.maxTick) state.tick = 0;
  setPlaying(!state.playing);
};
$('scrub').oninput = () => { state.tick = parseFloat($('scrub').value); };
$('view-orbit').onclick = () => setViewMode('orbit');
$('view-camera').onclick = () => setViewMode('camera');
$('active-track').onchange = () => {
  state.activeTrack = parseInt($('active-track').value);
  if (state.plan) {
    renderStrips(state.plan);
    applyHighlight();
  }
};
$('highlight').onchange = applyHighlight;

boot();
requestAnimationFrame(animate);

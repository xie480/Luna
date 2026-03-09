<!-- <script setup>
import { ref, onMounted, onBeforeUnmount } from "vue";
import * as PIXI from "pixi.js";
import { Live2DModel } from "pixi-live2d-display/cubism4";

const props = defineProps({
  modelUrl: { type: String, required: true }
});

const canvasRef = ref(null);

let app = null;
let container = null;
let model = null;

// 拖动状态
let dragging = false;
let lastPos = { x: 0, y: 0 };

// 全体偏移 & 缩放
const view = { x: 0, y: 0, scale: 1 };

function startDrag(e) {
  dragging = true;
  lastPos.x = e.clientX;
  lastPos.y = e.clientY;
}

function onMouseMove(e) {
  if (!dragging) return;
  const dx = e.clientX - lastPos.x;
  const dy = e.clientY - lastPos.y;
  lastPos.x = e.clientX;
  lastPos.y = e.clientY;
  view.x += dx;
  view.y += dy;
  container.position.set(view.x, view.y);
}

function stopDrag() {
  dragging = false;
}

function onWheel(e) {
  const oldScale = view.scale;
  view.scale *= (e.deltaY > 0 ? 0.95 : 1.05);
  view.scale = Math.min(3, Math.max(0.01, view.scale));

  const rect = canvasRef.value.getBoundingClientRect();
  const cx = rect.width / 2;
  const cy = rect.height / 2;
  const factor = view.scale / oldScale;

  view.x = (view.x - cx) * factor + cx;
  view.y = (view.y - cy) * factor + cy;

  container.scale.set(view.scale);
  container.position.set(view.x, view.y);
}

async function initPixi() {
  window.PIXI = PIXI; // 必须设置给 Live2D 插件读取

  app = new PIXI.Application({
    view: canvasRef.value,
    backgroundAlpha: 0,
    resizeTo: canvasRef.value.parentElement,
  });

  container = new PIXI.Container();
  app.stage.addChild(container);

  try {
    model = await Live2DModel.from(props.modelUrl, {
      ticker: PIXI.Ticker.shared
    });

    model.anchor.set(0.5, 1);
    model.x = app.renderer.width / 2;
    model.y = app.renderer.height;
    container.addChild(model);
  } catch (err) {
    console.error("Live2D 加载失败:", err);
  }
}

onMounted(() => {
  initPixi();
  window.addEventListener("mousemove", onMouseMove);
  window.addEventListener("mouseup", stopDrag);
});

onBeforeUnmount(() => {
  window.removeEventListener("mousemove", onMouseMove);
  window.removeEventListener("mouseup", stopDrag);
  if (app) app.destroy(true);
});
</script>

<template>
  <div
    class="live2d-wrap"
    @mousedown.prevent="startDrag"
    @wheel.prevent="onWheel"
  >
    <canvas ref="canvasRef"></canvas>
  </div>
</template>

<style scoped>
.live2d-wrap {
  width: 100%;
  height: 100%;
  user-select: none;
  touch-action: none;
}
</style> -->

<template>
  <div
    class="floating-panel-shell"
    @mouseenter="$emit('mouseenter')"
    @mouseleave="$emit('mouseleave')"
  >
    <div
      class="floating-panel"
      :class="[panelClass, { fullscreen: isFullscreen }]"
      :style="panelStyle"
    >
      <div class="panel-header" @mousedown="startDrag">
        <div class="header-copy">
          <slot name="header-left">
            <div class="title-block">
              <span class="title-main">{{ title }}</span>
              <span v-if="subtitle" class="title-sub">{{ subtitle }}</span>
            </div>
          </slot>
        </div>

        <div class="header-actions">
          <slot name="header-actions" />
          <button class="header-btn" @click.stop="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
            {{ isFullscreen ? "🗗" : "🗖" }}
          </button>
          <button class="close-btn" @click="$emit('close')">×</button>
        </div>
      </div>

      <div class="panel-body" :class="bodyClass">
        <slot />
      </div>

      <template v-if="!isFullscreen">
        <div class="resize-handle sw" @mousedown.stop="startResize($event, 'sw')"></div>
        <div class="resize-handle se" @mousedown.stop="startResize($event, 'se')"></div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from "vue";

const props = defineProps({
  title: { type: String, default: "" },
  subtitle: { type: String, default: "" },
  panelClass: { type: String, default: "" },
  bodyClass: { type: String, default: "" },
  initialX: { type: Number, default: 140 },
  initialY: { type: Number, default: 80 },
  initialWidth: { type: Number, default: 1120 },
  initialHeight: { type: Number, default: 720 },
  minWidth: { type: Number, default: 820 },
  minHeight: { type: Number, default: 520 },
});

defineEmits(["close", "mouseenter", "mouseleave"]);

const x = ref(props.initialX);
const y = ref(props.initialY);
const width = ref(props.initialWidth);
const height = ref(props.initialHeight);

const isFullscreen = ref(false);
const prevRect = ref({
  x: x.value,
  y: y.value,
  w: width.value,
  h: height.value,
});

const panelStyle = computed(() => {
  if (isFullscreen.value) {
    return {
      left: "0px",
      top: "0px",
      width: "100vw",
      height: "100vh",
    };
  }

  return {
    left: `${x.value}px`,
    top: `${y.value}px`,
    width: `${width.value}px`,
    height: `${height.value}px`,
  };
});

function saveRect() {
  prevRect.value = {
    x: x.value,
    y: y.value,
    w: width.value,
    h: height.value,
  };
}

function toggleFullscreen() {
  if (!isFullscreen.value) {
    saveRect();
    isFullscreen.value = true;
    return;
  }

  isFullscreen.value = false;
  x.value = prevRect.value.x;
  y.value = prevRect.value.y;
  width.value = prevRect.value.w;
  height.value = prevRect.value.h;
}

let isDragging = false;
let dragOffset = { x: 0, y: 0 };

function startDrag(event) {
  if (isFullscreen.value) return;
  if (event.target.closest(".close-btn") || event.target.closest(".header-btn")) return;

  isDragging = true;
  dragOffset = {
    x: event.clientX - x.value,
    y: event.clientY - y.value,
  };

  window.addEventListener("mousemove", onDrag);
  window.addEventListener("mouseup", stopDrag);
}

function onDrag(event) {
  if (!isDragging) return;

  x.value = Math.min(Math.max(event.clientX - dragOffset.x, -width.value + 160), window.innerWidth - 80);
  y.value = Math.min(Math.max(event.clientY - dragOffset.y, -40), window.innerHeight - 80);
}

function stopDrag() {
  isDragging = false;
  window.removeEventListener("mousemove", onDrag);
  window.removeEventListener("mouseup", stopDrag);
}

let resizeDir = "";
let resizeStart = { x: 0, y: 0 };
let initialRect = { x: 0, y: 0, w: 0, h: 0 };

function startResize(event, dir) {
  if (isFullscreen.value) return;

  resizeDir = dir;
  resizeStart = { x: event.clientX, y: event.clientY };
  initialRect = {
    x: x.value,
    y: y.value,
    w: width.value,
    h: height.value,
  };

  window.addEventListener("mousemove", onResize);
  window.addEventListener("mouseup", stopResize);
}

function onResize(event) {
  const dx = event.clientX - resizeStart.x;
  const dy = event.clientY - resizeStart.y;

  if (resizeDir.includes("e")) {
    width.value = Math.max(props.minWidth, initialRect.w + dx);
  }

  if (resizeDir.includes("s")) {
    height.value = Math.max(props.minHeight, initialRect.h + dy);
  }

  if (resizeDir.includes("w")) {
    const nextWidth = Math.max(props.minWidth, initialRect.w - dx);
    x.value = initialRect.x + (initialRect.w - nextWidth);
    width.value = nextWidth;
  }
}

function stopResize() {
  window.removeEventListener("mousemove", onResize);
  window.removeEventListener("mouseup", stopResize);
}

onBeforeUnmount(() => {
  stopDrag();
  stopResize();
});
</script>

<style scoped>
.floating-panel-shell {
  position: fixed;
  inset: 0;
  z-index: 9750;
  pointer-events: none;
}

.floating-panel {
  position: fixed;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  pointer-events: auto;
  border-radius: 12px;
  border: 1px solid var(--border, rgba(0,255,200,0.26));
  background:
    radial-gradient(circle at top right, color-mix(in oklab, var(--primary, #00ffc8) 12%, transparent), transparent 42%),
    radial-gradient(circle at bottom left, color-mix(in oklab, var(--primary-2, #00aaff) 10%, transparent), transparent 36%),
    var(--bg-panel, linear-gradient(145deg, rgba(7,12,22,0.96), rgba(4,8,18,0.96)));
  box-shadow: var(--shadow-panel, 0 24px 70px rgba(0,0,0,0.78));
  color: var(--text-main, #fff);
  backdrop-filter: blur(14px) saturate(120%);
}

.floating-panel.fullscreen {
  border-radius: 0;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 12px 16px;
  border-bottom: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 58%, transparent);
  background: color-mix(in oklab, black 78%, transparent);
  cursor: move;
  user-select: none;
}

.header-copy {
  min-width: 0;
}

.title-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.title-main {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--primary, #00ffc8);
}

.title-sub {
  font-size: 11px;
  color: var(--text-dim, #8aa5b6);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.header-btn {
  width: 24px;
  height: 22px;
  border-radius: 4px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.2)) 72%, transparent);
  background: var(--bg-panel-soft, rgba(255,255,255,0.08));
  color: var(--text-main, #fff);
  cursor: pointer;
  line-height: 1;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.header-btn:hover {
  border-color: var(--primary, #00ffc8);
  box-shadow: var(--glow-primary, 0 0 8px rgba(0,255,200,0.24));
}

.close-btn {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 20px;
  cursor: pointer;
  transition: transform 0.18s ease;
}

.close-btn:hover {
  transform: scale(1.08);
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.resize-handle {
  position: absolute;
  bottom: 0;
  width: 14px;
  height: 14px;
}

.resize-handle.sw {
  left: 0;
  cursor: sw-resize;
}

.resize-handle.se {
  right: 0;
  cursor: se-resize;
}
</style>

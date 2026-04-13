<template>
  <div class="diff-viewer">
    <div v-if="!lines.length" class="empty">暂无差异</div>
    <div v-for="(line, index) in lines" :key="index" class="diff-line" :class="lineClass(line)">
      {{ line }}
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  lines: { type: Array, default: () => [] },
});

function lineClass(line) {
  if (String(line).startsWith("+ ")) return "add";
  if (String(line).startsWith("- ")) return "remove";
  return "";
}
</script>

<style scoped>
.diff-viewer {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 45%, transparent);
  background: rgba(0,0,0,0.28);
  min-height: 120px;
  max-height: 320px;
  overflow: auto;
  font-family: "Consolas", "Monaco", monospace;
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.diff-line {
  padding: 2px 4px;
  border-radius: 4px;
  color: #d7efe6;
}

.diff-line.add {
  background: rgba(34,197,94,0.14);
  color: #9ae6b4;
}

.diff-line.remove {
  background: rgba(239,68,68,0.14);
  color: #fda4af;
}

.empty {
  color: var(--text-dim, #8ca0af);
}
</style>

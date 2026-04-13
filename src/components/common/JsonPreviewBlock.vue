<template>
  <div class="json-block">
    <div v-if="title" class="block-title">{{ title }}</div>
    <pre class="json-content" :style="{ maxHeight: maxHeight + 'px' }">{{ content }}</pre>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { stringifyPretty } from "../../utils/data-utils.js";

const props = defineProps({
  title: { type: String, default: "" },
  value: { type: [Object, Array, String, Number, Boolean, null], default: "" },
  maxHeight: { type: Number, default: 220 },
});

const content = computed(() => stringifyPretty(props.value, ""));
</script>

<style scoped>
.json-block {
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 45%, transparent);
  border-radius: 8px;
  background: rgba(0,0,0,0.28);
  overflow: hidden;
}

.block-title {
  padding: 8px 10px;
  border-bottom: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 45%, transparent);
  color: var(--primary, #00ffc8);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.json-content {
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: "Consolas", "Monaco", monospace;
  font-size: 11px;
  line-height: 1.55;
  color: #d7efe6;
}

.json-content::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.json-content::-webkit-scrollbar-thumb {
  background: color-mix(in oklab, var(--primary, #00ffc8) 35%, transparent);
  border-radius: 999px;
}
</style>

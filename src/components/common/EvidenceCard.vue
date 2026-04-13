<template>
  <div class="evidence-card">
    <div class="evidence-top">
      <div class="copy">
        <strong>{{ evidence.title || evidence.id || "未命名证据" }}</strong>
        <span class="meta">{{ evidence.source || "-" }} · {{ evidence.role || "-" }}</span>
      </div>
      <StatusBadge :label="scoreLabel" tone="RUNNING" />
    </div>

    <div class="content">{{ evidence.content || "-" }}</div>

    <div v-if="metadataText" class="metadata">{{ metadataText }}</div>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { stringifyPretty } from "../../utils/data-utils.js";
import StatusBadge from "./StatusBadge.vue";

const props = defineProps({
  evidence: {
    type: Object,
    default: () => ({}),
  },
});

const scoreLabel = computed(() => {
  const score = props.evidence?.score;
  if (score === null || score === undefined || score === "") return "score -";
  const numeric = Number(score);
  if (Number.isNaN(numeric)) return `score ${score}`;
  return `score ${numeric.toFixed(3)}`;
});

const metadataText = computed(() => {
  const value = props.evidence?.metadata;
  if (!value || (typeof value === "object" && Object.keys(value).length === 0)) return "";
  return stringifyPretty(value, "");
});
</script>

<style scoped>
.evidence-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 45%, transparent);
  background: rgba(255,255,255,0.04);
}

.evidence-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
}

.copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.copy strong {
  font-size: 12px;
  color: var(--text-main, #eefaf5);
}

.meta {
  font-size: 11px;
  color: var(--text-dim, #9bb2c4);
}

.content {
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-main, #d8e8ef);
}

.metadata {
  padding-top: 8px;
  border-top: 1px dashed color-mix(in oklab, var(--border, rgba(255,255,255,0.08)) 45%, transparent);
  font-family: "Consolas", "Monaco", monospace;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-word;
  color: #afd8c6;
}
</style>

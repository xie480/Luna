<template>
  <span class="status-badge" :class="toneClass">
    {{ label || status || "-" }}
  </span>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  status: { type: String, default: "" },
  label: { type: String, default: "" },
  tone: { type: String, default: "" },
});

const toneClass = computed(() => {
  const raw = String(props.tone || props.status || "").toUpperCase();
  if (["SUCCESS", "ACTIVE", "ENABLED", "OK", "COMPLETED", "TRUE"].includes(raw)) return "ok";
  if (["ERROR", "FAILED", "DISABLED", "ERR", "FALSE", "REJECTED"].includes(raw)) return "err";
  if (["RUNNING", "PENDING", "WAIT", "DRAFT", "THINKING", "SYNC"].includes(raw)) return "run";
  if (["WARNING", "HIGH", "MEDIUM", "ARCHIVED"].includes(raw)) return "warn";
  return "";
});
</script>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid color-mix(in oklab, var(--border, rgba(255,255,255,0.16)) 70%, transparent);
  background: rgba(255,255,255,0.04);
  color: var(--text-dim, #c6d3db);
  font-size: 11px;
  line-height: 1.4;
  white-space: nowrap;
}

.status-badge.ok {
  color: #9ae6b4;
  border-color: rgba(34,197,94,0.45);
  background: rgba(34,197,94,0.12);
}

.status-badge.err {
  color: #fda4af;
  border-color: rgba(239,68,68,0.45);
  background: rgba(239,68,68,0.12);
}

.status-badge.run {
  color: #7dd3fc;
  border-color: rgba(59,130,246,0.45);
  background: rgba(59,130,246,0.12);
}

.status-badge.warn {
  color: #fcd34d;
  border-color: rgba(245,158,11,0.45);
  background: rgba(245,158,11,0.12);
}
</style>

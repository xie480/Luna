<template>
  <JsonPreviewBlock :title="title" :value="schemaObject" :maxHeight="maxHeight" />
</template>

<script setup>
import { computed } from "vue";
import { parseJsonInput } from "../../utils/data-utils.js";
import JsonPreviewBlock from "./JsonPreviewBlock.vue";

const props = defineProps({
  title: { type: String, default: "Schema" },
  schema: { type: [String, Object, Array], default: "" },
  maxHeight: { type: Number, default: 220 },
});

const schemaObject = computed(() => parseJsonInput(props.schema, props.schema || {}));
</script>

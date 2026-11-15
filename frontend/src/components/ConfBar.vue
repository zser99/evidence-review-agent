<script setup>
import { confColor } from '../format'
defineProps({ value: { type: Number, default: null }, threshold: { type: Number, default: 0.85 } })
</script>

<template>
  <span v-if="value === null || value === undefined" class="small-muted">-</span>
  <span v-else class="confbar" :title="`확신도 ${(value * 100).toFixed(1)}% · 자동 판정 기준 ${(threshold * 100).toFixed(0)}%`">
    <span class="track"><span class="fill d-block" :style="{ width: Math.max(2, value * 100) + '%', background: confColor(value, threshold) }"></span></span>
    <span class="num" :style="{ color: confColor(value, threshold) }">{{ (value * 100).toFixed(1) }}</span>
  </span>
</template>

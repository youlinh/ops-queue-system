<script setup lang="ts">
import { iconPaths, type AppIconName } from './icons'

withDefaults(defineProps<{
  name: AppIconName
  decorative?: boolean
}>(), {
  decorative: false,
})
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    :aria-hidden="decorative ? 'true' : undefined"
  >
    <path v-for="path in iconPaths[name]" :key="path" :d="path" />
  </svg>
</template>

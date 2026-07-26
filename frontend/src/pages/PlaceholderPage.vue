<script setup lang="ts">
defineProps<{ title: string }>()
</script>

<template>
  <section class="empty-panel">
    <p class="eyebrow">MODULE READY</p>
    <h2>{{ title }}</h2>
    <p>页面框架与权限已就绪，业务数据将在对应功能模块中加载。</p>
  </section>
</template>

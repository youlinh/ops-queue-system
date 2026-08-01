<template>
  <section class="recovery-page ui-panel">
    <p class="eyebrow">404 · PAGE NOT FOUND</p>
    <h1>页面未找到</h1>
    <p>你访问的地址不存在，或已被移动。</p>
    <RouterLink class="inline-link" to="/workspace">返回工作台</RouterLink>
  </section>
</template>

<style scoped>
.recovery-page {
  max-width: 620px;
  margin: min(16vh, 160px) auto;
  padding: clamp(var(--ui-space-6), 6vw, 48px);
}

h1 {
  margin: 0;
  color: var(--ui-text);
  font-size: clamp(1.75rem, 4vw, 2.25rem);
  letter-spacing: -.03em;
}

p:not(.eyebrow) {
  margin: var(--ui-space-3) 0 0;
  color: var(--ui-text-body);
  line-height: 1.6;
}

.inline-link {
  display: inline-flex;
  min-height: var(--ui-action-min-height);
  align-items: center;
  margin-top: var(--ui-space-5);
  font-weight: 600;
  text-decoration: none;
}
</style>

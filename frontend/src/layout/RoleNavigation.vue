<script setup lang="ts">
import type { Role } from '@/features/auth/auth.types'
import { computed, ref } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import { mobileNavigationFor, navigationFor } from './navigation'

const props = defineProps<{ roles: readonly Role[] }>()
const emit = defineEmits<{ navigate: [] }>()
const items = computed(() => navigationFor(props.roles))
const mobileItems = computed(() => mobileNavigationFor(props.roles))
const moreOpen = ref(false)

function navigate(): void {
  moreOpen.value = false
  emit('navigate')
}
</script>

<template>
  <nav class="role-navigation role-navigation--desktop" aria-label="主导航">
    <RouterLink
      v-for="item in items"
      :key="item.to"
      :to="item.to"
      class="nav-link"
      active-class="nav-link--active"
      :aria-label="item.label"
      @click="navigate"
    >
      <AppIcon :name="item.icon" decorative class="nav-icon" />
      <span class="nav-label" data-nav-label>{{ item.label }}</span>
    </RouterLink>
  </nav>

  <nav class="role-navigation role-navigation--mobile" aria-label="主导航">
    <RouterLink
      v-for="item in mobileItems.primary"
      :key="item.to"
      :to="item.to"
      class="mobile-nav-action"
      active-class="mobile-nav-action--active"
      @click="navigate"
    >
      <AppIcon :name="item.icon" decorative />
      <span>{{ item.label }}</span>
    </RouterLink>
    <button
      v-if="mobileItems.overflow.length > 0"
      class="mobile-nav-action"
      type="button"
      aria-haspopup="dialog"
      aria-controls="mobile-navigation-dialog-title"
      @click="moreOpen = true"
    >
      <AppIcon name="more" decorative />
      <span>更多</span>
    </button>
  </nav>

  <AppDialog
    :open="moreOpen"
    labelled-by="mobile-navigation-dialog-title"
    @close="moreOpen = false"
  >
    <div class="mobile-navigation-dialog">
      <div class="mobile-navigation-dialog__header">
        <h2 id="mobile-navigation-dialog-title">更多导航</h2>
        <button type="button" aria-label="关闭更多导航" @click="moreOpen = false">
          <AppIcon name="close" decorative />
        </button>
      </div>
      <nav aria-label="更多导航">
        <RouterLink
          v-for="item in mobileItems.overflow"
          :key="item.to"
          :to="item.to"
          class="mobile-overflow-link"
          @click="navigate"
        >
          <AppIcon :name="item.icon" decorative />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
    </div>
  </AppDialog>
</template>

<style scoped>
.role-navigation--desktop { display: grid; gap: var(--ui-space-1); padding: var(--ui-space-5) var(--ui-space-3); }
.nav-link, .mobile-nav-action, .mobile-overflow-link { color: inherit; text-decoration: none; }
.nav-link { display: flex; min-height: var(--ui-action-min-height); align-items: center; gap: var(--ui-space-3); padding: 0 var(--ui-space-3); border-radius: var(--ui-radius-control); color: var(--ui-text-secondary); }
.nav-link:hover, .nav-link--active { color: var(--ui-text); background: var(--ui-hover); }
.nav-icon, .mobile-nav-action :deep(svg), .mobile-overflow-link :deep(svg), .mobile-navigation-dialog__header :deep(svg) { width: 22px; height: 22px; flex: 0 0 auto; }
.role-navigation--mobile { display: none; }
.mobile-navigation-dialog { min-width: min(100%, 300px); padding: var(--ui-space-5); }
.mobile-navigation-dialog__header { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--ui-space-4); }
.mobile-navigation-dialog__header h2 { margin: 0; font-size: 1.125rem; }
.mobile-navigation-dialog__header button { display: grid; width: var(--ui-action-min-height); height: var(--ui-action-min-height); place-items: center; border: 0; border-radius: 50%; color: var(--ui-text); background: transparent; }
.mobile-overflow-link { display: flex; min-height: var(--ui-action-min-height); align-items: center; gap: var(--ui-space-3); padding: 0 var(--ui-space-2); border-radius: var(--ui-radius-control); color: var(--ui-text); }
.mobile-overflow-link:hover, .mobile-overflow-link:focus-visible { background: var(--ui-hover); }
@media (min-width: 681px) and (max-width: 920px) { .role-navigation--desktop { padding: var(--ui-space-4) var(--ui-space-2); } .nav-link { justify-content: center; padding: 0; } .nav-label { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; } }
@media (max-width: 680px) { .role-navigation--desktop { display: none; } .role-navigation--mobile { position: fixed; z-index: 20; right: 0; bottom: 0; left: 0; display: grid; grid-auto-columns: minmax(0, 1fr); grid-auto-flow: column; min-height: calc(64px + env(safe-area-inset-bottom)); padding: 0; gap: 0; padding-bottom: env(safe-area-inset-bottom); border-top: var(--ui-border-width) solid var(--ui-hairline); background: color-mix(in srgb, var(--ui-surface) 88%, transparent); backdrop-filter: blur(20px) saturate(160%); } .mobile-nav-action { display: grid; min-width: 0; min-height: 64px; place-content: center; justify-items: center; gap: 3px; border: 0; color: var(--ui-text-secondary); background: transparent; font: inherit; font-size: .6875rem; } .mobile-nav-action--active { color: var(--ui-accent-link); } }
</style>
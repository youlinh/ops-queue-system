<script setup lang="ts">
import type { Role } from '@/features/auth/auth.types'
import { computed } from 'vue'
import { navigationFor } from './navigation'

const props = defineProps<{ roles: readonly Role[] }>()
const emit = defineEmits<{ navigate: [] }>()
const items = computed(() => navigationFor(props.roles))
</script>

<template>
  <nav class="role-navigation" aria-label="主导航">
    <RouterLink
      v-for="item in items"
      :key="item.to"
      :to="item.to"
      class="nav-link"
      active-class="nav-link--active"
      @click="emit('navigate')"
    >
      <span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>
      <span data-nav-label>{{ item.label }}</span>
    </RouterLink>
  </nav>
</template>

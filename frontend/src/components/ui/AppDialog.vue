<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { containFocus } from './useFocusContainment'

const props = defineProps<{
  open: boolean
  labelledBy: string
}>()

const emit = defineEmits<{
  close: []
}>()

const dialog = ref<HTMLElement | null>(null)
let stopFocusContainment: (() => void) | undefined

function requestClose() {
  emit('close')
}

function deactivate() {
  stopFocusContainment?.()
  stopFocusContainment = undefined
}

async function activate() {
  const returnTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  if (props.open && dialog.value) {
    stopFocusContainment = containFocus(dialog.value, returnTarget)
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
  }
}

watch(() => props.open, (isOpen) => {
  if (isOpen) {
    void activate()
  } else {
    deactivate()
  }
}, { immediate: true })

onBeforeUnmount(deactivate)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="ui-overlay" @click.self="requestClose">
      <section
        ref="dialog"
        class="ui-overlay__surface"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="labelledBy"
        tabindex="-1"
        @keydown="onKeydown"
      >
        <slot />
      </section>
    </div>
  </Teleport>
</template>

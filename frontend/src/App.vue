<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterView } from 'vue-router'
import { installPressFeedback } from './utils/press-feedback'

const root = ref<HTMLElement | null>(null)
let removePressFeedback: (() => void) | undefined
onMounted(() => {
  if (root.value) removePressFeedback = installPressFeedback(root.value)
})
onBeforeUnmount(() => removePressFeedback?.())
</script>

<template>
  <div ref="root" class="app-root">
    <RouterView />
  </div>
</template>

<style scoped>
.app-root {
  min-height: 100vh;
}
</style>

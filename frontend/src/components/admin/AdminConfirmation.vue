<script setup lang="ts">
import { onMounted, ref } from 'vue'
defineProps<{ title: string; label: string; busy: boolean; danger?: boolean }>()
const emit = defineEmits<{ cancel: []; confirm: [] }>()
const root = ref<HTMLElement>()
onMounted(() => {
  root.value?.focus()
  root.value?.scrollIntoView({ block: 'nearest' })
})
</script>
<template>
  <section
    ref="root"
    class="admin-confirm"
    role="alertdialog"
    tabindex="-1"
    aria-labelledby="admin-confirm-title"
    aria-describedby="admin-confirm-description"
    :aria-busy="busy"
    @keydown.esc="!busy && emit('cancel')"
  >
    <h3 id="admin-confirm-title">{{ title }}</h3>
    <div id="admin-confirm-description"><slot /></div>
    <div class="admin-confirm-actions">
      <button class="ui-button admin-button" type="button" :disabled="busy" @click="emit('cancel')">
        取消
      </button>
      <button
        class="ui-button admin-button"
        :class="{ danger }"
        type="button"
        :disabled="busy"
        @click="emit('confirm')"
      >
        {{ busy ? '正在提交…' : label }}
      </button>
    </div>
  </section>
</template>

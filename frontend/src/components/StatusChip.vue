<script setup lang="ts">
import { computed } from 'vue'
import type { ExecutionStatus } from '@/api/types'

const props = defineProps<{ status: ExecutionStatus }>()

const meta = computed(() => {
  switch (props.status) {
    case 'RUNNING':   return { color: 'info',    icon: 'mdi-play-circle', label: 'RUNNING' }
    case 'SUCCESS':   return { color: 'success', icon: 'mdi-check-circle', label: 'SUCCESS' }
    case 'FAILED':    return { color: 'error',   icon: 'mdi-alert-circle', label: 'FAILED' }
    case 'RECOVERED': return { color: 'warning', icon: 'mdi-restore',      label: 'RECOVERED' }
  }
})
</script>

<template>
  <v-chip :color="meta?.color" size="small" variant="flat" :prepend-icon="meta?.icon">
    {{ meta?.label }}
  </v-chip>
</template>

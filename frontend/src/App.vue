<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

const auth = useAuthStore()
const toast = useToastStore()

onMounted(() => {
  auth.bootstrap()
})
</script>

<template>
  <v-app>
    <router-view />
    <v-snackbar
      v-model="toast.open"
      :color="toast.color"
      :timeout="toast.timeout"
      location="top right"
    >
      {{ toast.message }}
      <template #actions>
        <v-btn variant="text" @click="toast.close">닫기</v-btn>
      </template>
    </v-snackbar>
  </v-app>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <v-main>
    <v-container class="d-flex flex-column align-center justify-center" style="min-height: 100vh;">
      <v-icon icon="mdi-map-search-outline" size="80" color="secondary" />
      <h1 class="text-h4 mt-4">페이지를 찾을 수 없습니다</h1>
      <p class="text-body-1 text-medium-emphasis mt-2">요청하신 경로가 존재하지 않습니다.</p>
      <v-btn color="primary" class="mt-6" @click="router.push('/')">홈으로</v-btn>
    </v-container>
  </v-main>
</template>

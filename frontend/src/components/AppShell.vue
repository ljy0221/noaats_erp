<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const toast = useToastStore()

const navItems = [
  { title: '대시보드', to: '/dashboard', icon: 'mdi-view-dashboard' },
  { title: '인터페이스 관리', to: '/interfaces', icon: 'mdi-format-list-bulleted' },
  { title: '실행 이력', to: '/history', icon: 'mdi-history' },
]

const currentTitle = computed(() => {
  const item = navItems.find((n) => route.path.startsWith(n.to))
  return item?.title ?? 'IFMS'
})

async function onLogout() {
  await auth.logout()
  toast.info('로그아웃되었습니다.')
  router.push({ name: 'login' })
}
</script>

<template>
  <v-layout>
    <v-app-bar color="primary" density="comfortable" elevation="1">
      <v-app-bar-nav-icon icon="mdi-view-dashboard-outline" />
      <v-app-bar-title>IFMS — {{ currentTitle }}</v-app-bar-title>
      <v-spacer />
      <span class="text-body-2 text-white mr-3" v-if="auth.username">
        {{ auth.username }}
      </span>
      <v-btn
        variant="text"
        color="white"
        prepend-icon="mdi-logout"
        @click="onLogout"
      >
        로그아웃
      </v-btn>
    </v-app-bar>

    <v-navigation-drawer permanent width="240">
      <v-list nav density="compact">
        <v-list-subheader>메뉴</v-list-subheader>
        <v-list-item
          v-for="item in navItems"
          :key="item.to"
          :prepend-icon="item.icon"
          :title="item.title"
          :to="item.to"
          :active="route.path.startsWith(item.to)"
        />
      </v-list>
    </v-navigation-drawer>

    <v-main>
      <v-container fluid class="pa-6">
        <router-view />
      </v-container>
    </v-main>
  </v-layout>
</template>

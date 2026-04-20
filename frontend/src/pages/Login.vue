<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { ApiError } from '@/api/types'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const toast = useToastStore()

const username = ref('operator@ifms.local')
const password = ref('operator1234')
const loading = ref(false)
const errorMessage = ref<string | null>(null)

async function onSubmit() {
  errorMessage.value = null
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    toast.success('로그인 성공')
    const redirect = (route.query.redirect as string) || '/interfaces'
    router.push(redirect)
  } catch (e) {
    if (e instanceof ApiError) {
      errorMessage.value = e.status === 401
        ? '아이디 또는 비밀번호가 올바르지 않습니다.'
        : e.message
    } else {
      errorMessage.value = '로그인 중 오류가 발생했습니다.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <v-main>
    <v-container class="d-flex align-center justify-center" style="min-height: 100vh;">
      <v-card width="420" elevation="4" class="pa-2">
        <v-card-title class="text-h5 d-flex align-center">
          <v-icon icon="mdi-shield-lock" color="primary" class="mr-2" />
          IFMS 로그인
        </v-card-title>
        <v-card-subtitle>인터페이스 통합관리 시스템</v-card-subtitle>

        <v-card-text>
          <v-form @submit.prevent="onSubmit">
            <v-text-field
              v-model="username"
              label="이메일"
              type="email"
              prepend-inner-icon="mdi-account"
              autocomplete="username"
              required
              class="mb-3"
            />
            <v-text-field
              v-model="password"
              label="비밀번호"
              type="password"
              prepend-inner-icon="mdi-lock"
              autocomplete="current-password"
              required
            />
            <v-alert
              v-if="errorMessage"
              type="error"
              density="compact"
              class="mt-3"
              variant="tonal"
            >
              {{ errorMessage }}
            </v-alert>

            <v-btn
              type="submit"
              color="primary"
              block
              size="large"
              class="mt-4"
              :loading="loading"
            >
              로그인
            </v-btn>
          </v-form>
          <v-alert
            type="info"
            density="compact"
            variant="tonal"
            class="mt-4 text-caption"
          >
            <strong>테스트 계정</strong><br>
            operator@ifms.local / operator1234 (OPERATOR)<br>
            admin@ifms.local / admin1234 (ADMIN+OPERATOR)
          </v-alert>
        </v-card-text>
      </v-card>
    </v-container>
  </v-main>
</template>

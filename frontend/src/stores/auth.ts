import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as apiLogin, logout as apiLogout, primeCsrf, probeAuthenticated } from '@/api/auth'
import { unauthenticatedBus, UNAUTH_EVENT } from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  const authenticated = ref<boolean>(false)
  const username = ref<string | null>(null)
  const ready = ref<boolean>(false)

  async function bootstrap() {
    if (ready.value) return
    authenticated.value = await probeAuthenticated()
    if (authenticated.value) {
      username.value = localStorage.getItem('ifms:username')
    }
    ready.value = true

    unauthenticatedBus.addEventListener(UNAUTH_EVENT, () => {
      handleUnauthenticated()
    })
  }

  async function login(u: string, password: string): Promise<void> {
    await apiLogin(u, password)
    username.value = u
    localStorage.setItem('ifms:username', u)
    authenticated.value = true
    await primeCsrf()
  }

  async function logout(): Promise<void> {
    try {
      await apiLogout()
    } finally {
      clear()
    }
  }

  function handleUnauthenticated() {
    if (!authenticated.value) return
    clear()
  }

  function clear() {
    authenticated.value = false
    username.value = null
    localStorage.removeItem('ifms:username')
  }

  return { authenticated, username, ready, bootstrap, login, logout }
})

import { defineStore } from 'pinia'
import { ref } from 'vue'

type ToastColor = 'success' | 'error' | 'warning' | 'info'

export const useToastStore = defineStore('toast', () => {
  const open = ref(false)
  const message = ref('')
  const color = ref<ToastColor>('info')
  const timeout = ref(4000)

  function show(msg: string, c: ToastColor = 'info', t = 4000) {
    message.value = msg
    color.value = c
    timeout.value = t
    open.value = true
  }

  const success = (msg: string) => show(msg, 'success')
  const error = (msg: string) => show(msg, 'error', 6000)
  const warning = (msg: string) => show(msg, 'warning')
  const info = (msg: string) => show(msg, 'info')

  function close() {
    open.value = false
  }

  return { open, message, color, timeout, show, success, error, warning, info, close }
})

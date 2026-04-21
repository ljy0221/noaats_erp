import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vuetify({ autoImport: true }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
      '/login': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        // GET /login은 SPA 라우트(LoginView)로 서빙, POST만 Spring formLogin으로 프록시.
        // bypass가 path를 반환하면 Vite가 SPA index.html로 폴백한다.
        bypass: (req) => (req.method === 'POST' ? undefined : req.url),
      },
      '/logout': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        bypass: (req) => (req.method === 'POST' ? undefined : req.url),
      },
    },
  },
})

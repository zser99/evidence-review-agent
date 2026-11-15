import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 개발 서버는 /api 를 Spring Boot(8080)로 넘긴다. 운영에서는 Nginx가 같은 일을 한다.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': { target: process.env.VITE_API_TARGET || 'http://localhost:8080', changeOrigin: true } },
  },
  build: { chunkSizeWarningLimit: 900 },
})

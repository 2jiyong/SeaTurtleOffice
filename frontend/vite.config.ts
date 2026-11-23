import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 중에는 /api 요청을 Spring 서버로 넘긴다. 다른 주소면 BACKEND_URL로 바꾼다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.BACKEND_URL ?? 'http://localhost:8080',
    },
  },
})

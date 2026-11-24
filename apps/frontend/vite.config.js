import process from 'node:process'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api/v1/': process.env.PROOFFLOW_API_TARGET || 'http://127.0.0.1:8080',
    },
  },
})

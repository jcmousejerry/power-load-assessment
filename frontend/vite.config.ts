import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, '.', '')
  const backendUrl = environment.VITE_BACKEND_URL || 'http://127.0.0.1:8080'
  return ({
  plugins: [react()],
  // sockjs-client still reads `global.crypto`, which is a Node-style global.
  // In browsers, globalThis is the equivalent standard global object.
  define: {
    global: 'globalThis',
  },
  build: {
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': backendUrl,
      '/ws': {
        target: backendUrl,
        ws: true,
      },
    },
  },
  })
})

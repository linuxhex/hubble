import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  build: {
    outDir: path.resolve(__dirname, '../hubble-server/src/main/resources/static'),
    emptyOutDir: true
  },
  server: {
    host: '0.0.0.0',
    port: 82,
    proxy: {
      '/api/ws': {
        target: 'http://localhost:18081',
        ws: true,
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:18081',
        changeOrigin: true
      }
    }
  }
})

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_TARGET || 'http://127.0.0.1:8081'
  return {
    plugins: [vue(), { name: 'verify-local-proxy', configureServer(server) { server.middlewares.use((_request,response,next) => { response.setHeader('X-Light-Mfg-Backend-Target',target);next() }) } }],
    server: { port: Number(env.VITE_PORT || 5173), strictPort: true, proxy: { '/api': { target, changeOrigin: false } } },
    build: { rollupOptions: { output: { manualChunks: { element: ['element-plus'], charts: ['echarts'] } } } }
  }
})

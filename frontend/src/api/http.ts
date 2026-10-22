import axios from 'axios'
import type { Page } from '../types'

export const api = axios.create({ baseURL: '/api/v1', withCredentials: true, timeout: 25000 })
let csrf: string | undefined
export function clearCsrf() { csrf = undefined }
api.interceptors.request.use(async config => {
  if (!['get', 'head', 'options'].includes(config.method || 'get')) {
    if (!csrf) {
      const response = await axios.get<{ token: string }>('/api/v1/auth/csrf', { withCredentials: true })
      csrf = response.data.token
    }
    config.headers.set('X-CSRF-TOKEN', csrf)
  }
  return config
})
api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401 && !error.config?.url?.includes('/auth/login') && !error.config?.url?.includes('/auth/me')) {
    clearCsrf()
    window.dispatchEvent(new CustomEvent('session-expired'))
  }
  if (error.response?.data?.code === 'CSRF_INVALID') clearCsrf()
  return Promise.reject(error)
})
export function errorText(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data
    const message = body?.message || (error.code === 'ECONNABORTED' ? '请求超时，请重试' : '网络连接失败，请检查服务后重试')
    return `${message}${body?.traceId ? `（追踪号：${body.traceId}）` : ''}`
  }
  return error instanceof Error ? error.message : '操作失败，请重试'
}
export async function loadPage<T>(path: string, params?: Record<string, unknown>): Promise<Page<T>> { return (await api.get<Page<T>>(path, { params })).data }

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, clearCsrf } from '../api/http'
import type { CurrentUser } from '../types'
export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const loaded = ref(false)
  const permissions = computed(() => new Set(user.value?.permissions || []))
  function can(permission: string | string[]): boolean { return Array.isArray(permission) ? permission.some(p => permissions.value.has(p)) : permissions.value.has(permission) }
  async function load() {
    try { user.value = (await api.get<CurrentUser>('/auth/me')).data; loaded.value = true; return true }
    catch (error: any) { if (error.response?.status === 401) { user.value = null; loaded.value = true; clearCsrf(); return false }; throw error }
  }
  async function login(username: string, password: string) { await api.post('/auth/login', { username, password }); clearCsrf(); await load() }
  async function logout() { try { await api.post('/auth/logout') } finally { clear() } }
  function clear() { user.value = null; loaded.value = false; clearCsrf() }
  return { user, loaded, can, load, login, logout, clear }
})

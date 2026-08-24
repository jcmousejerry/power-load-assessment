import axios from 'axios'
import { useAuthStore } from '../store/auth'
import type { ApiResponse } from '../types'

export const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().user?.token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (body && body.success === false) {
      return Promise.reject(new Error(body.message || '请求处理失败'))
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      useAuthStore.getState().logout()
      window.location.href = '/login'
    }
    const message = error.response?.data?.message || error.message || '网络请求失败'
    return Promise.reject(new Error(message))
  },
)

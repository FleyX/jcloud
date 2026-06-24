import { useNotificationStore } from '@/store/notification'
import { useUserStore } from '@/store/user'
import router from '@/router'
import type { ApiResponse } from '@/types/auth'

const BASE_URL = '/jcloud/api'

async function handleResponse<T>(response: Response): Promise<T> {
  const json = (await response.json()) as ApiResponse<T>
  if (json.code === 401) {
    const userStore = useUserStore()
    userStore.logoutAction()
    router.push('/login')
    throw new Error(json.msg || '登录已过期')
  }
  if (json.code !== 200) {
    const message = json.msg || '请求失败'
    const notificationStore = useNotificationStore()
    notificationStore.error(message)
    throw new Error(message)
  }
  return json.data
}

function getHeaders(): HeadersInit {
  const userStore = useUserStore()
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
  }
  if (userStore.token) {
    headers.Authorization = `Bearer ${userStore.token}`
  }
  return headers
}

export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  let fullUrl = `${BASE_URL}${url}`
  if (params) {
    const query = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        query.append(key, String(value))
      }
    })
    const queryString = query.toString()
    if (queryString) {
      fullUrl += `?${queryString}`
    }
  }
  const response = await fetch(fullUrl, {
    method: 'GET',
    headers: getHeaders(),
  })
  return handleResponse<T>(response)
}

export async function post<T>(url: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE_URL}${url}`, {
    method: 'POST',
    headers: getHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  })
  return handleResponse<T>(response)
}

export async function put<T>(url: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE_URL}${url}`, {
    method: 'PUT',
    headers: getHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  })
  return handleResponse<T>(response)
}

export async function del<T>(url: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE_URL}${url}`, {
    method: 'DELETE',
    headers: getHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  })
  return handleResponse<T>(response)
}

export async function patch<T>(url: string, body?: unknown): Promise<T> {
  const response = await fetch(`${BASE_URL}${url}`, {
    method: 'PATCH',
    headers: getHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  })
  return handleResponse<T>(response)
}

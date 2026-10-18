const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')

let bearerToken: string | null = null

/**
 * Le jeton est porte par l'application (choisi dans la barre de roles), jamais fabrique
 * ni decode dans le navigateur : la console affiche et transmet un jeton emis par le
 * backend (endpoint de developpement en profil dev, annuaire en production).
 */
export function setToken(token: string | null): void {
  bearerToken = token
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export function apiGet<T>(path: string): Promise<T> {
  return request<T>('GET', path)
}

export function apiPost<T>(path: string, body: unknown): Promise<T> {
  return request<T>('POST', path, body)
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (bearerToken) {
    headers.Authorization = `Bearer ${bearerToken}`
  }
  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = response.statusText
    try {
      const parsed = (await response.json()) as { code?: string; message?: string }
      code = parsed.code ?? code
      message = parsed.message ?? message
    } catch {
      // corps non JSON : on garde le message HTTP
    }
    throw new ApiError(response.status, code, message)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

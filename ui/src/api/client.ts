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
  /** Ecarts de validation du formulaire (FRM-03), vides pour les autres refus. */
  readonly violations: string[]

  constructor(status: number, code: string, message: string, violations: string[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.violations = violations
  }
}

export function apiGet<T>(path: string): Promise<T> {
  return request<T>('GET', path)
}

export function apiPost<T>(path: string, body: unknown): Promise<T> {
  return request<T>('POST', path, body)
}

/**
 * Envoi multipart (FRM-05) : le navigateur fixe lui-meme le `Content-Type` avec sa
 * frontiere, un en-tete explicite casserait l'analyse cote serveur.
 */
export function apiUpload<T>(path: string, form: FormData): Promise<T> {
  return request<T>('POST', path, form)
}

/**
 * Telechargement authentifie : le jeton vit dans la memoire de l'application et non dans
 * un cookie, un simple lien ne pourrait donc pas s'authentifier ; on recupere le contenu
 * puis on declenche l'enregistrement local depuis un objet temporaire.
 */
export async function apiDownload(path: string, filename: string): Promise<void> {
  const headers: Record<string, string> = { Accept: '*/*' }
  if (bearerToken) {
    headers.Authorization = `Bearer ${bearerToken}`
  }
  const response = await fetch(`${BASE_URL}${path}`, { method: 'GET', headers })
  if (!response.ok) {
    throw await failure(response)
  }
  const url = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.append(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const multipart = body instanceof FormData
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined && !multipart) {
    headers['Content-Type'] = 'application/json'
  }
  if (bearerToken) {
    headers.Authorization = `Bearer ${bearerToken}`
  }
  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body:
      body === undefined
        ? undefined
        : multipart
          ? (body as FormData)
          : JSON.stringify(body),
  })
  if (!response.ok) {
    throw await failure(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

/** Corps d'erreur du moteur (contrat `ErrorBody` / `FormErrorBody`) converti en exception. */
async function failure(response: Response): Promise<ApiError> {
  let code = `HTTP_${response.status}`
  let message = response.statusText
  let violations: string[] = []
  try {
    const parsed = (await response.json()) as {
      code?: string
      message?: string
      violations?: string[]
    }
    code = parsed.code ?? code
    message = parsed.message ?? message
    violations = parsed.violations ?? violations
  } catch {
    // corps non JSON : on garde le message HTTP
  }
  // HTTP/2 ne fournit pas de statusText : le refus doit rester lisible.
  return new ApiError(response.status, code, message || code, violations)
}


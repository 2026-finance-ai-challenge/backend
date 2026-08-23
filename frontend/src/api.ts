import type { ApiProblem, InvestorType, Profile, TokenPair } from './types'

const configuredBase = (import.meta as ImportMeta & { env?: Record<string, string | undefined> })
  .env?.VITE_API_BASE_URL?.trim()
export const API_BASE = configuredBase ? configuredBase.replace(/\/$/, '') : ''

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly retryAfter?: string

  constructor(problem: ApiProblem) {
    super(problem.message || 'The request could not be completed.')
    this.name = 'ApiError'
    this.status = problem.status || 500
    this.code = problem.code || 'UNKNOWN_ERROR'
    this.retryAfter = problem.retryAfter
  }
}

type Listener = (profile: Profile | null) => void

class SessionVault {
  private accessToken: string | null = null
  private refreshToken: string | null = null
  private profile: Profile | null = null
  private listeners = new Set<Listener>()
  private refreshPromise: Promise<boolean> | null = null

  get user(): Profile | null { return this.profile }
  get bearer(): string | null { return this.accessToken }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  accept(pair: TokenPair): void {
    this.accessToken = pair.accessToken
    this.refreshToken = pair.refreshToken
    this.profile = pair.user
    this.listeners.forEach((listener) => listener(this.profile))
  }

  clear(): void {
    this.accessToken = null
    this.refreshToken = null
    this.profile = null
    this.listeners.forEach((listener) => listener(null))
  }

  async refresh(): Promise<boolean> {
    if (!this.refreshToken) return false
    if (this.refreshPromise) return this.refreshPromise
    const token = this.refreshToken
    this.refreshPromise = fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: token }),
      credentials: 'same-origin',
    }).then(async (response) => {
      if (!response.ok) {
        this.clear()
        return false
      }
      this.accept(await response.json() as TokenPair)
      return true
    }).catch(() => {
      this.clear()
      return false
    }).finally(() => { this.refreshPromise = null })
    return this.refreshPromise
  }

  async logout(): Promise<void> {
    const refreshToken = this.refreshToken
    try {
      if (refreshToken && this.accessToken) {
        await fetch(`${API_BASE}/api/v1/auth/logout`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.accessToken}` },
          body: JSON.stringify({ refreshToken }),
        })
      }
    } finally {
      this.clear()
    }
  }
}

export const session = new SessionVault()

async function problemFrom(response: Response): Promise<ApiProblem> {
  const retryAfter = response.headers.get('Retry-After') || undefined
  try {
    const body = await response.json() as ApiProblem
    return { ...body, status: response.status, retryAfter }
  } catch {
    return { status: response.status, message: response.statusText, retryAfter }
  }
}

export async function api<T>(path: string, init: RequestInit = {}, allowRefresh = true): Promise<T> {
  const headers = new Headers(init.headers)
  if (session.bearer) headers.set('Authorization', `Bearer ${session.bearer}`)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  headers.set('Accept', 'application/json')
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers, credentials: 'same-origin' })
  if (response.status === 401 && allowRefresh && await session.refresh()) {
    return api<T>(path, init, false)
  }
  if (!response.ok) throw new ApiError(await problemFrom(response))
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export function queryString(values: Record<string, string | number | boolean | null | undefined>): string {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') params.set(key, String(value))
  })
  return params.size ? `?${params.toString()}` : ''
}

export async function login(loginId: string, password: string): Promise<Profile> {
  const pair = await api<TokenPair>('/api/v1/auth/login', {
    method: 'POST', body: JSON.stringify({ loginId, password }),
  }, false)
  session.accept(pair)
  return pair.user
}

export async function signup(input: {
  loginId: string
  password: string
  passwordConfirm: string
  nationality: string
  investorType: InvestorType
  termsAccepted: boolean
  privacyAccepted: boolean
}): Promise<Profile> {
  return api<Profile>('/api/v1/auth/signup', {
    method: 'POST',
    body: JSON.stringify(input),
  }, false)
}

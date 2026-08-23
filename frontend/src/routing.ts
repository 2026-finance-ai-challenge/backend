export type RouteName =
  | 'home'
  | 'search'
  | 'screener'
  | 'news'
  | 'news-detail'
  | 'dart'
  | 'filing-detail'
  | 'stock-detail'
  | 'tax'
  | 'account'
  | 'auth'

export type AppLocation = {
  route: RouteName
  id?: string
  query: URLSearchParams
}

const staticRoutes = new Set<RouteName>(['home', 'search', 'screener', 'news', 'dart', 'tax', 'account', 'auth'])

export function parseAppLocation(hash: string): AppLocation {
  const value = hash.replace(/^#\/?/, '')
  const [path = '', rawQuery = ''] = value.split('?', 2)
  const parts = path.split('/').filter(Boolean)
  const rawRoute = parts[0] || 'home'
  const query = new URLSearchParams(rawQuery)
  if (staticRoutes.has(rawRoute as RouteName)) return { route: rawRoute as RouteName, query }
  if (rawRoute === 'stock' && /^[0-9A-Za-z]{6}$/.test(parts[1] || '')) {
    return { route: 'stock-detail', id: parts[1].toUpperCase(), query }
  }
  if (rawRoute === 'filing' && /^[0-9]{14}$/.test(parts[1] || '')) {
    return { route: 'filing-detail', id: parts[1], query }
  }
  if (rawRoute === 'article' && /^[0-9a-f-]{36}$/i.test(parts[1] || '')) {
    return { route: 'news-detail', id: parts[1], query }
  }
  return { route: 'home', query: new URLSearchParams() }
}

export function appHash(route: RouteName, id?: string, params?: Record<string, string>): string {
  const path = route === 'stock-detail' ? `stock/${id}`
    : route === 'filing-detail' ? `filing/${id}`
      : route === 'news-detail' ? `article/${id}`
        : route
  const query = new URLSearchParams(params)
  const suffix = query.size ? `?${query.toString()}` : ''
  return `#/${path}${suffix}`
}

export function navigate(route: RouteName, id?: string, params?: Record<string, string>): void {
  window.location.hash = appHash(route, id, params)
}

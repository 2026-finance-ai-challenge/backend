import { FormEvent, ReactNode, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { api, ApiError, queryString, session } from './api'
import { appHash, navigate, type AppLocation, type RouteName } from './routing'
import type { NewsArticle, Profile, Stock } from './types'

export type Locale = 'en' | 'ko'

export function useProfile(): Profile | null {
  return useSyncExternalStore(
    (listener) => session.subscribe(listener),
    () => session.user,
    () => null,
  )
}

export function useRemote<T>(loader: () => Promise<T>, dependencies: readonly unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState(0)
  useEffect(() => {
    let current = true
    setLoading(true)
    setError(null)
    loader().then((value) => { if (current) setData(value) })
      .catch((reason: unknown) => { if (current) setError(reason instanceof ApiError ? reason : new ApiError({ message: String(reason) })) })
      .finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [...dependencies, nonce]) // eslint-disable-line react-hooks/exhaustive-deps
  return { data, error, loading, retry: () => setNonce((value) => value + 1), setData }
}

export function formatNumber(value: number | null | undefined, options?: Intl.NumberFormatOptions): string {
  return value === null || value === undefined
    ? 'Unavailable'
    : new Intl.NumberFormat('en-US', options).format(value)
}

export function formatDate(value: string | null | undefined, dateOnly = false): string {
  if (!value) return 'Not available'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.valueOf())) return value
  return new Intl.DateTimeFormat('en-US', dateOnly
    ? { year: 'numeric', month: 'short', day: 'numeric' }
    : { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(parsed)
}

export function StatusPanel({ title, message, action, tone = 'neutral' }: {
  title: string
  message: string
  action?: { label: string; run: () => void }
  tone?: 'neutral' | 'warning' | 'error'
}) {
  return <div className={`status-panel ${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
    <span className="status-icon" aria-hidden="true">{tone === 'error' ? '!' : tone === 'warning' ? '△' : '○'}</span>
    <div><strong>{title}</strong><p>{message}</p></div>
    {action && <button className="text-button" onClick={action.run}>{action.label}</button>}
  </div>
}

export function RemoteBoundary<T>({ state, children, empty, className }: {
  state: ReturnType<typeof useRemote<T>>
  children: (value: T) => ReactNode
  empty?: (value: T) => boolean
  className?: string
}) {
  if (state.loading && state.data === null) return <div className={`skeleton-stack ${className || ''}`} aria-label="Loading"><i /><i /><i /></div>
  if (state.error) {
    const rateLimited = state.error.status === 429
    return <StatusPanel
      title={rateLimited ? 'Usage limit reached' : 'This section is temporarily unavailable'}
      message={rateLimited && state.error.retryAfter ? `Try again after ${state.error.retryAfter}.` : state.error.message}
      action={{ label: 'Retry', run: state.retry }} tone={rateLimited ? 'warning' : 'error'}
    />
  }
  if (state.data === null) return null
  if (empty?.(state.data)) return <StatusPanel title="No matching data" message="Try a different filter or reset the current search." />
  return <>{children(state.data)}</>
}

function SearchBox() {
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<Stock[]>([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const profile = useProfile()
  const timer = useRef<number | undefined>(undefined)

  useEffect(() => () => window.clearTimeout(timer.current), [])

  const search = (value: string) => {
    setQuery(value)
    window.clearTimeout(timer.current)
    if (!value.trim()) { setItems([]); setOpen(false); return }
    timer.current = window.setTimeout(() => {
      setLoading(true)
      api<{ items: Stock[] }>(`/api/v1/market/stocks/search${queryString({ query: value.trim(), limit: 8 })}`)
        .then((result) => { setItems(result.items); setOpen(true) })
        .catch(() => { setItems([]); setOpen(true) })
        .finally(() => setLoading(false))
    }, 180)
  }

  const toggleWatchlist = async (event: React.MouseEvent, stock: Stock) => {
    event.preventDefault()
    event.stopPropagation()
    if (!profile) { navigate('auth', undefined, { returnTo: appHash('search', undefined, { q: query }) }); return }
    const next = !stock.watchlisted
    setItems((current) => current.map((item) => item.stockCode === stock.stockCode ? { ...item, watchlisted: next } : item))
    try {
      await api(`/api/v1/me/watchlist/${stock.stockCode}`, { method: next ? 'PUT' : 'DELETE' })
    } catch {
      setItems((current) => current.map((item) => item.stockCode === stock.stockCode ? { ...item, watchlisted: !next } : item))
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (query.trim()) navigate('search', undefined, { q: query.trim() })
  }

  return <form className="global-search" role="search" onSubmit={submit} onBlur={() => window.setTimeout(() => setOpen(false), 150)}>
    <span aria-hidden="true">⌕</span>
    <input value={query} onChange={(event) => search(event.target.value)} onFocus={() => query && setOpen(true)}
      aria-label="Search supported stocks" placeholder="Search name or 6-digit code" autoComplete="off" />
    {loading && <span className="mini-loader" aria-label="Searching" />}
    {open && <div className="search-popover">
      {items.map((stock) => <a key={stock.stockCode} href={appHash('stock-detail', stock.stockCode)}>
        <span className="stock-avatar">{stock.nameEn?.slice(0, 1) || stock.nameKo.slice(0, 1)}</span>
        <span><b>{stock.nameEn || stock.nameKo}</b><small>{stock.nameKo} · {stock.stockCode} · {stock.market}</small></span>
        <button type="button" className="heart" aria-label={`${stock.watchlisted ? 'Remove from' : 'Add to'} watchlist`}
          onClick={(event) => void toggleWatchlist(event, stock)}>{stock.watchlisted ? '♥' : '♡'}</button>
      </a>)}
      {!loading && items.length === 0 && <div className="search-empty">No supported stock found in the 75-stock universe.</div>}
      <button type="submit" className="view-all">View all results for “{query}” →</button>
    </div>}
  </form>
}

type NotificationItem = { id: string; title: string; body: string; referenceType: string | null; referenceId: string | null; read: boolean; createdAt: string }

function NotificationMenu() {
  const profile = useProfile()
  const [open, setOpen] = useState(false)
  const [items, setItems] = useState<NotificationItem[]>([])
  const unread = items.filter((item) => !item.read).length
  useEffect(() => {
    if (!profile) { setItems([]); return }
    void api<{ items: NotificationItem[] }>('/api/v1/me/notifications?limit=20').then((result) => setItems(result.items)).catch(() => setItems([]))
  }, [profile])
  const toggle = async () => {
    if (!profile) { navigate('auth'); return }
    setOpen((value) => !value)
    if (!open) {
      try { setItems((await api<{ items: NotificationItem[] }>('/api/v1/me/notifications?limit=20')).items) } catch { setItems([]) }
    }
  }
  const openItem = async (item: NotificationItem) => {
    if (!item.read) {
      await api(`/api/v1/me/notifications/${item.id}/read`, { method: 'PUT' })
      setItems((all) => all.map((current) => current.id === item.id ? { ...current, read: true } : current))
    }
    setOpen(false)
    if (item.referenceType === 'STOCK' && item.referenceId) navigate('stock-detail', item.referenceId)
    if (item.referenceType === 'NEWS' && item.referenceId) navigate('news-detail', item.referenceId)
    if (item.referenceType === 'FILING' && item.referenceId) navigate('filing-detail', item.referenceId)
    if (item.referenceType === 'TAX') navigate('tax')
  }
  return <div className="popover-anchor">
    <button className="icon-button" onClick={() => void toggle()} aria-label="Notifications">♧{unread > 0 && <b>{unread}</b>}</button>
    {open && <div className="notification-popover">
      <div className="popover-title"><b>Notifications</b><button onClick={() => void api('/api/v1/me/notifications/read-all', { method: 'PUT' }).then(() => setItems((all) => all.map((item) => ({ ...item, read: true }))))}>Mark all read</button></div>
      {items.map((item) => <button className={`notification-item ${item.read ? '' : 'unread'}`} key={item.id} onClick={() => void openItem(item)}><b>{item.title}</b><p>{item.body}</p><small>{formatDate(item.createdAt)}</small></button>)}
      {!items.length && <p className="empty-copy">No notifications yet.</p>}
    </div>}
  </div>
}

type RawRoom = { id: string; name: string; context: { type: string; referenceId: string | null; version: string | null; title: string }; version: number; updatedAt: string; lastMessageAt: string | null }
type RawMessage = { id: string; sequence: number; role: 'USER' | 'ASSISTANT'; content: string; citations: Array<{ id: string; title: string; excerpt: string; url: string | null }>; insufficientEvidence: boolean; refusalReason: string | null; disclaimer: string | null; createdAt: string }
type RawGeneration = { id: string; status: string; errorCode: string | null }

export type AgentContext = { type: 'GENERAL' | 'STOCK' | 'NEWS' | 'FILING' | 'TAX_GUIDE'; referenceId?: string; title?: string }

export function openAgent(prompt = ''): void {
  window.dispatchEvent(new CustomEvent('kmarket:open-agent', { detail: { prompt } }))
}

function AgentPanel({ context, close, initialPrompt, promptNonce }: { context: AgentContext; close: () => void; initialPrompt: string; promptNonce: number }) {
  const profile = useProfile()
  const [rooms, setRooms] = useState<RawRoom[]>([])
  const [room, setRoom] = useState<RawRoom | null>(null)
  const [messages, setMessages] = useState<RawMessage[]>([])
  const [input, setInput] = useState('')
  const [generation, setGeneration] = useState<RawGeneration | null>(null)
  const [drawer, setDrawer] = useState(false)
  const [roomQuery, setRoomQuery] = useState('')
  const [error, setError] = useState('')

  const reloadRooms = async () => {
    if (!profile) return
    const result = await api<RawRoom[]>('/api/v1/me/chats')
    setRooms(result)
    if (!room) {
      const contextual = context.referenceId
        ? result.find((item) => item.context.type === context.type && item.context.referenceId === context.referenceId)
        : result[0]
      setRoom(contextual || null)
    }
  }
  useEffect(() => { void reloadRooms().catch(() => setError('Chat rooms could not be loaded.')) }, [profile]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (initialPrompt) setInput(initialPrompt) }, [initialPrompt, promptNonce])
  useEffect(() => {
    if (!room) { setMessages([]); return }
    api<RawMessage[]>(`/api/v1/me/chats/${room.id}/messages`).then(setMessages).catch(() => setError('Messages could not be loaded.'))
  }, [room])
  useEffect(() => {
    if (!room || !generation || !['PENDING', 'PROCESSING'].includes(generation.status)) return
    const timer = window.setInterval(async () => {
      try {
        const current = await api<RawGeneration>(`/api/v1/me/chats/${room.id}/generations/${generation.id}`)
        setGeneration(current)
        if (!['PENDING', 'PROCESSING'].includes(current.status)) {
          setMessages(await api<RawMessage[]>(`/api/v1/me/chats/${room.id}/messages`))
          void reloadRooms()
        }
      } catch { setError('The answer status could not be refreshed.') }
    }, 1200)
    return () => window.clearInterval(timer)
  }, [generation, room]) // eslint-disable-line react-hooks/exhaustive-deps

  const createRoom = async () => {
    if (!profile) { navigate('auth'); return }
    try {
      const created = await api<RawRoom>('/api/v1/me/chats', {
        method: 'POST', body: JSON.stringify({ contextType: context.type, referenceId: context.referenceId || null }),
      })
      setRooms((all) => [created, ...all]); setRoom(created); setDrawer(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'A new chat could not be created.') }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!room || !input.trim()) return
    const content = input.trim(); setInput(''); setError('')
    try {
      const result = await api<{ userMessage: RawMessage; generation: RawGeneration }>(`/api/v1/me/chats/${room.id}/messages`, {
        method: 'POST', body: JSON.stringify({ clientMessageId: crypto.randomUUID(), content, selectedSectionId: null, selectedText: null }),
      })
      setMessages((all) => [...all, result.userMessage]); setGeneration(result.generation)
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Your message was not sent.') }
  }

  const rename = async (target: RawRoom) => {
    const name = window.prompt('Chat name', target.name)?.trim()
    if (!name) return
    try {
      const updated = await api<RawRoom>(`/api/v1/me/chats/${target.id}/name`, { method: 'PUT', body: JSON.stringify({ name, expectedVersion: target.version }) })
      setRooms((all) => all.map((item) => item.id === updated.id ? updated : item)); if (room?.id === updated.id) setRoom(updated)
    } catch { setError('The room changed elsewhere. Refresh and try again.') }
  }

  const remove = async (target: RawRoom) => {
    if (!window.confirm('Delete this chat? This chat will be removed from your chat history.')) return
    await api(`/api/v1/me/chats/${target.id}`, { method: 'DELETE' })
    const rest = rooms.filter((item) => item.id !== target.id); setRooms(rest); if (room?.id === target.id) setRoom(rest[0] || null)
  }

  const regenerate = async (message: RawMessage) => {
    if (!room) return
    setError('')
    try {
      setGeneration(await api<RawGeneration>(`/api/v1/me/chats/${room.id}/messages/${message.id}/regenerate`, { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID() }) }))
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'The answer could not be regenerated.') }
  }

  const visibleRooms = rooms.filter((item) => item.name.toLowerCase().includes(roomQuery.trim().toLowerCase()))

  if (!profile) return <aside className="agent-panel" aria-label="AI Agent"><div className="agent-head"><b>K-Market AI</b><button onClick={close}>×</button></div><StatusPanel title="Sign in to start a chat" message="Your rooms and conversation history are protected by account ownership checks." action={{ label: 'Sign in', run: () => navigate('auth') }} /></aside>

  return <aside className="agent-panel" aria-label="AI Agent">
    <div className="agent-head"><button onClick={() => setDrawer((value) => !value)} aria-label="Chat rooms">☰</button><div><small>{room?.context.type || context.type}</small><b>{room?.name || 'New market chat'}</b></div><button onClick={close} aria-label="Close AI Agent">×</button></div>
    {drawer && <div className="chat-drawer"><button className="primary compact" onClick={() => void createRoom()}>＋ New chat</button><input className="chat-search" value={roomQuery} onChange={(event) => setRoomQuery(event.target.value)} placeholder="Search chats" aria-label="Search chat rooms" />{visibleRooms.map((item) => <div className={room?.id === item.id ? 'active' : ''} key={item.id}><button onClick={() => { setRoom(item); setDrawer(false) }}><b>{item.name}</b><small>{item.context.type} · {formatDate(item.lastMessageAt)}</small></button><button onClick={() => void rename(item)}>✎</button><button onClick={() => void remove(item)}>×</button></div>)}{!visibleRooms.length && <p className="empty-copy">No matching chats.</p>}</div>}
    <div className="context-chip"><b>{room?.context.type || context.type}</b><span>{room?.context.title || context.title || 'Korean market information'}</span></div>
    <div className="messages">
      {!room && <div className="agent-welcome"><span>AI</span><h3>Ask with a verified context</h3><p>Live price, filing and news claims are retrieved by the server. The model does not invent current market values.</p><button className="primary" onClick={() => void createRoom()}>Start this chat</button></div>}
      {room && !messages.length && <div className="quick-prompts"><p>Try asking:</p>{['Summarize the current context', 'What risks should a global investor verify?', 'Show me the supporting sources'].map((text) => <button key={text} onClick={() => setInput(text)}>{text}</button>)}</div>}
      {messages.map((message) => <article className={`message ${message.role.toLowerCase()}`} key={message.id}><b>{message.role === 'USER' ? 'You' : 'K-Market AI'}</b><p>{message.content}</p>{message.insufficientEvidence && <span className="evidence-warning">Insufficient filing evidence</span>}{message.citations?.map((citation) => <a key={citation.id} href={citation.url || '#'} target={citation.url ? '_blank' : undefined} rel="noreferrer">↗ {citation.title}</a>)}{message.disclaimer && <small>{message.disclaimer}</small>}<div className="message-actions"><button onClick={() => void navigator.clipboard.writeText(message.content)}>Copy</button>{message.role === 'ASSISTANT' && <button onClick={() => void regenerate(message)}>Regenerate</button>}</div></article>)}
      {generation && ['PENDING', 'PROCESSING'].includes(generation.status) && <div className="generating"><span /><p>Checking server sources and composing an answer…</p><button onClick={() => room && void api<RawGeneration>(`/api/v1/me/chats/${room.id}/generations/${generation.id}/stop`, { method: 'POST' }).then(setGeneration)}>Stop</button></div>}
      {generation?.status === 'FAILED' && <StatusPanel title="AI answer failed" message={generation.errorCode || 'The provider could not complete this answer.'} action={{ label: 'Retry', run: () => room && void api<RawGeneration>(`/api/v1/me/chats/${room.id}/generations/${generation.id}/retry`, { method: 'POST' }).then(setGeneration) }} tone="error" />}
      {error && <p className="agent-error" role="alert">{error}</p>}
    </div>
    <form className="agent-compose" onSubmit={submit}><textarea rows={2} value={input} onChange={(event) => setInput(event.target.value)} placeholder="Ask about the Korean market…" maxLength={4000} disabled={!room} /><button type="submit" aria-label="Send message">↑</button><small>Information only — not investment, legal or tax advice.</small></form>
  </aside>
}

const nav: Array<{ route: RouteName; label: string; labelKo: string }> = [
  { route: 'home', label: 'Market', labelKo: '시장' }, { route: 'screener', label: 'Screener', labelKo: '종목 탐색' },
  { route: 'news', label: 'News', labelKo: '뉴스' }, { route: 'dart', label: 'DART Intelligence', labelKo: '공시 인텔리전스' }, { route: 'tax', label: 'Tax Guide', labelKo: '세무 가이드' },
]

export function AppShell({ location, context, children }: { location: AppLocation; context: AgentContext; children: ReactNode }) {
  const profile = useProfile()
  const [agent, setAgent] = useState(false)
  const [agentPrompt, setAgentPrompt] = useState({ value: '', nonce: 0 })
  const [mobileMenu, setMobileMenu] = useState(false)
  const [locale, setLocale] = useState<Locale>('en')
  useEffect(() => setMobileMenu(false), [location.route, location.id])
  useEffect(() => { document.documentElement.lang = locale === 'ko' ? 'ko' : 'en' }, [locale])
  useEffect(() => {
    const open = (event: Event) => {
      const prompt = event instanceof CustomEvent && typeof event.detail?.prompt === 'string' ? event.detail.prompt : ''
      setAgentPrompt((current) => ({ value: prompt, nonce: current.nonce + 1 }))
      setAgent(true)
    }
    window.addEventListener('kmarket:open-agent', open)
    return () => window.removeEventListener('kmarket:open-agent', open)
  }, [])
  const active = location.route === 'stock-detail' ? 'screener' : location.route === 'filing-detail' ? 'dart' : location.route === 'news-detail' ? 'news' : location.route
  return <div className={`app-shell ${agent ? 'agent-open' : ''}`}>
    <header className="gnb">
      <a className="brand" href={appHash('home')} aria-label="K-Market Navigator home"><span>K</span><b>K-Market<small>Navigator</small></b></a>
      <button className="mobile-menu" onClick={() => setMobileMenu((value) => !value)} aria-label="Toggle navigation">☰</button>
      <nav className={mobileMenu ? 'open' : ''}>{nav.map((item) => <a className={active === item.route ? 'active' : ''} key={item.route} href={appHash(item.route)}>{item.label}{locale === 'ko' && <small>{item.labelKo}</small>}</a>)}</nav>
      <SearchBox />
      <div className="gnb-actions"><button className="language" onClick={() => setLocale(locale === 'en' ? 'ko' : 'en')} aria-label="Change language">{locale.toUpperCase()}</button><NotificationMenu /><button className="agent-trigger" onClick={() => setAgent(true)}>✦ <span>AI Agent</span></button>{profile ? <a className="profile-link" href={appHash('account')}><span>{profile.loginId.slice(0, 1).toUpperCase()}</span><b>{profile.loginId}</b></a> : <a className="login-link" href={appHash('auth')}>Log in</a>}</div>
    </header>
    {locale === 'ko' && <div className="locale-note" role="status">English primary · 주요 탐색 레이블을 한국어로 함께 표시합니다.</div>}
    <main>{children}</main>
    {agent && <AgentPanel context={context} close={() => setAgent(false)} initialPrompt={agentPrompt.value} promptNonce={agentPrompt.nonce} />}
    {agent && <button className="agent-backdrop" onClick={() => setAgent(false)} aria-label="Close AI Agent overlay" />}
  </div>
}

export function InsightCards({ what, why, impact, loading = false }: { what?: string | null; why?: string | null; impact?: string | null; loading?: boolean }) {
  if (loading) return <div className="insight-grid"><div className="skeleton" /><div className="skeleton" /><div className="skeleton" /></div>
  return <div className="insight-grid">
    {[['What', what], ['Why', why], ['Impact', impact]].map(([label, value], index) => <article key={label}><span>0{index + 1}</span><h3>{label}</h3><p>{value || 'This insight is not available yet.'}</p></article>)}
  </div>
}

export function NewsCard({ article }: { article: NewsArticle }) {
  return <a className="news-card" href={appHash('news-detail', article.id)}>
    <div className="news-image">{article.thumbnailUrl ? <img src={article.thumbnailUrl} alt="" loading="lazy" referrerPolicy="no-referrer" /> : <span>KM</span>}</div>
    <div className="news-tags"><span className={`sentiment ${(article.sentiment || '').toLowerCase()}`}>{article.sentiment || 'Pending'}</span><span>{article.importance || 'Unrated'}</span>{article.relatedCoverageCount > 1 && <span>＋{article.relatedCoverageCount - 1} related</span>}</div>
    <h3>{article.englishTitle || article.originalTitle}</h3><p>{article.what || article.originalExcerpt || 'AI analysis is pending.'}</p>
    <footer><span>{article.publisher}</span><time>{formatDate(article.publishedAt)}</time></footer>
  </a>
}

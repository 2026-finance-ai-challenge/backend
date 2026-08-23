import { Component, ErrorInfo, ReactNode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api } from './api'
import { AppShell, StatusPanel, type AgentContext, useProfile } from './components'
import { parseAppLocation, type AppLocation } from './routing'
import { AccountPage, AuthPage, TaxPage } from './pages/account'
import { DartPage, FilingDetailPage, NewsDetailPage, NewsPageView } from './pages/intelligence'
import { HomePage, ScreenerPage, SearchPage, StockPage } from './pages/market'
import './styles.css'

class AppErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error('UI boundary', error.name, info.componentStack) }
  render() {
    if (this.state.failed) return <div className="fatal-state"><StatusPanel title="This screen could not be rendered" message="No inferred market data was shown. Reload the application to retry." action={{ label: 'Reload', run: () => window.location.reload() }} tone="error" /></div>
    return this.props.children
  }
}

function useLocation(): AppLocation {
  const [location, setLocation] = useState(() => parseAppLocation(window.location.hash))
  useEffect(() => {
    if (!window.location.hash) window.location.hash = '#/home'
    const update = () => { setLocation(parseAppLocation(window.location.hash)); window.scrollTo({ top: 0, behavior: 'instant' }) }
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])
  return location
}

function contextFor(location: AppLocation): AgentContext {
  if (location.route === 'stock-detail') return { type: 'STOCK', referenceId: location.id, title: `Stock ${location.id}` }
  if (location.route === 'news-detail') return { type: 'NEWS', referenceId: location.id, title: 'Current news article' }
  if (location.route === 'filing-detail') return { type: 'FILING', referenceId: location.id, title: `DART filing ${location.id}` }
  if (location.route === 'tax') return { type: 'TAX_GUIDE', title: 'Tax guide' }
  return { type: 'GENERAL', title: 'Korean market' }
}

function RouteView({ location }: { location: AppLocation }) {
  switch (location.route) {
    case 'home': return <HomePage />
    case 'search': return <SearchPage query={location.query.get('q') || ''} />
    case 'screener': return <ScreenerPage />
    case 'news': return <NewsPageView />
    case 'news-detail': return <NewsDetailPage articleId={location.id!} />
    case 'dart': return <DartPage />
    case 'filing-detail': return <FilingDetailPage receiptNumber={location.id!} />
    case 'stock-detail': return <StockPage stockCode={location.id!} />
    case 'tax': return <TaxPage />
    case 'account': return <AccountPage />
    case 'auth': return <AuthPage returnTo={location.query.get('returnTo') || undefined} />
  }
}

function RecentlyViewedTracker({ location }: { location: AppLocation }) {
  const profile = useProfile()
  useEffect(() => {
    if (!profile || !location.id) return
    const itemType = location.route === 'stock-detail' ? 'STOCK' : location.route === 'filing-detail' ? 'FILING' : location.route === 'news-detail' ? 'NEWS' : null
    if (!itemType) return
    void api('/api/v1/me/recently-viewed', {
      method: 'POST',
      body: JSON.stringify({ itemType, referenceId: location.id, stockCode: itemType === 'STOCK' ? location.id : null }),
    }).catch(() => undefined)
  }, [location.id, location.route, profile])
  return null
}

function App() {
  const location = useLocation()
  if (location.route === 'auth') return <RouteView location={location} />
  return <AppShell location={location} context={contextFor(location)}><RecentlyViewedTracker location={location} /><RouteView location={location} /></AppShell>
}

createRoot(document.getElementById('root')!).render(<AppErrorBoundary><App /></AppErrorBoundary>)

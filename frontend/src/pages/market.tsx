import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import { api, queryString, session } from '../api'
import {
  formatDate, formatNumber, InsightCards, NewsCard, openAgent, RemoteBoundary, StatusPanel, useProfile, useRemote,
} from '../components'
import { appHash, navigate } from '../routing'
import type { Filing, GlobalPeer, NewsArticle, Stock, StockDetail } from '../types'

type Index = { indexCode: string; indexName: string; currentValue: number | null; changeRate: number | null; status: string; asOf: string | null; source: string }
type ForeignMonitor = { stock: Stock; policy: { warningThreshold: number }; warning: boolean; prediction: StockDetail['foreignLimitPrediction'] }
type FilingPage = { items: Filing[]; nextCursor: string | null }
type NewsPage = { items: NewsArticle[]; nextCursor: string | null }

function HeroSearch() {
  const [value, setValue] = useState('')
  const [items, setItems] = useState<Stock[]>([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const profile = useProfile()
  const timer = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearTimeout(timer.current), [])
  const search = (input: string) => {
    setValue(input)
    window.clearTimeout(timer.current)
    if (!input.trim()) { setItems([]); setOpen(false); return }
    timer.current = window.setTimeout(() => {
      setLoading(true)
      api<{ items: Stock[] }>(`/api/v1/market/stocks/search${queryString({ query: input.trim(), limit: 6 })}`)
        .then((result) => { setItems(result.items); setOpen(true) })
        .catch(() => { setItems([]); setOpen(true) })
        .finally(() => setLoading(false))
    }, 180)
  }
  const toggle = async (event: React.MouseEvent, stock: Stock) => {
    event.preventDefault(); event.stopPropagation()
    if (!profile) { navigate('auth', undefined, { returnTo: appHash('home') }); return }
    const next = !stock.watchlisted
    setItems((current) => current.map((item) => item.stockCode === stock.stockCode ? { ...item, watchlisted: next } : item))
    try { await api(`/api/v1/me/watchlist/${stock.stockCode}`, { method: next ? 'PUT' : 'DELETE' }) }
    catch { setItems((current) => current.map((item) => item.stockCode === stock.stockCode ? { ...item, watchlisted: !next } : item)) }
  }
  const submit = (event: FormEvent) => { event.preventDefault(); if (value.trim()) navigate('search', undefined, { q: value.trim() }) }
  return <form className="hero-search" onSubmit={submit} onBlur={() => window.setTimeout(() => setOpen(false), 150)}><span>⌕</span><input value={value} onChange={(event) => search(event.target.value)} onFocus={() => value && setOpen(true)} placeholder="Search Samsung, NAVER, 005930…" aria-label="Search Korean stocks" autoComplete="off" />{loading && <span className="mini-loader" aria-label="Searching" />}<button>Explore</button>{open && <div className="hero-search-results">{items.map((stock) => <div className="search-result-row" key={stock.stockCode}><a href={appHash('stock-detail', stock.stockCode)}><span className="stock-avatar">{stock.nameEn?.slice(0, 1) || stock.nameKo.slice(0, 1)}</span><span><b>{stock.nameEn || stock.nameKo}</b><small>{stock.nameKo} · {stock.stockCode} · {stock.market}</small></span></a><button type="button" className="heart" aria-label={`${stock.watchlisted ? 'Remove from' : 'Add to'} watchlist`} onClick={(event) => void toggle(event, stock)}>{stock.watchlisted ? '♥' : '♡'}</button></div>)}{!loading && !items.length && <p>No supported stock found in the 75-stock universe.</p>}<button className="view-all">View all results for “{value}” →</button></div>}</form>
}

export function HomePage() {
  const indices = useRemote(() => api<Index[]>('/api/v1/market/indices'), [])
  const stocks = useRemote(() => api<{ items: Stock[] }>('/api/v1/market/stocks?sort=VOLUME_DESC&limit=6'), [])
  const news = useRemote(() => api<NewsPage>('/api/v1/news?sort=IMPORTANCE&limit=6'), [])
  const filings = useRemote(() => api<FilingPage>('/api/v1/disclosures?limit=6'), [])
  const limits = useRemote(() => api<ForeignMonitor[]>('/api/v1/market/foreign-limits'), [])
  return <>
    <section className="hero page-section">
      <div className="hero-copy"><p className="eyebrow">GLOBAL KOREAN MARKET INTELLIGENCE</p><h1>See the Korean market.<br /><em>Understand what moves it.</em></h1><p>English-first prices, news, filings and trading caution signals for 75 supported KOSPI and KOSDAQ stocks.</p><HeroSearch /><div className="source-line"><span>Verified sources</span><b>KIS</b><b>KRX</b><b>OpenDART</b><b>Naver News</b></div></div>
      <div className="hero-orbit" aria-hidden="true"><div className="market-globe"><span>KOSPI</span><b>75</b><small>SUPPORTED STOCKS</small></div><i /><i /><i /><div className="floating-card ai"><span>✦ AI INSIGHT</span><b>Grounded answers</b><small>with source citations</small></div><div className="floating-card live"><span>● MARKET STATUS</span><b>Source-aware</b><small>stale data never looks live</small></div></div>
    </section>
    <section className="quick-actions page-section"><a href={appHash('news')}><span>01</span><b>Hot News</b><small>AI-ranked market events</small></a><a href={appHash('dart')}><span>02</span><b>DART Intelligence</b><small>Grounded filing research</small></a><a href={appHash('screener', undefined, { limit: 'foreign' })}><span>03</span><b>Foreign Limit Monitor</b><small>Four regulated stocks</small></a><a href={appHash('tax')}><span>04</span><b>Tax Eligibility</b><small>Treaty data and documents</small></a></section>
    <section className="page-section snapshot"><div className="section-head"><div><p className="eyebrow">MARKET SNAPSHOT</p><h2>Korea at a glance</h2></div><DataLegend /></div>
      <RemoteBoundary state={indices} empty={(value) => !value.length}>{(value) => <div className="index-grid">{value.map((index) => <article key={index.indexCode}><span>{index.indexName}</span><b>{formatNumber(index.currentValue, { maximumFractionDigits: 2 })}</b><strong className={(index.changeRate || 0) >= 0 ? 'up' : 'down'}>{index.changeRate === null ? 'Unavailable' : `${index.changeRate >= 0 ? '+' : ''}${index.changeRate.toFixed(2)}%`}</strong><small>{index.status} · {formatDate(index.asOf)}</small></article>)}</div>}</RemoteBoundary>
      <div className="split-heading"><h3>Popular stocks</h3><a href={appHash('screener')}>Open screener →</a></div>
      <RemoteBoundary state={stocks} empty={(value) => !value.items.length}>{(value) => <div className="stock-strip">{value.items.map((stock) => <StockMiniCard key={stock.stockCode} stock={stock} />)}</div>}</RemoteBoundary>
    </section>
    <section className="page-section dark-section"><div className="section-head light"><div><p className="eyebrow">AI NEWS INTELLIGENCE</p><h2>Know what happened.<br />Understand why it matters.</h2></div><a href={appHash('news')}>View all news →</a></div><RemoteBoundary state={news} empty={(value) => !value.items.length}>{(value) => <div className="news-grid">{value.items.map((article) => <NewsCard key={article.id} article={article} />)}</div>}</RemoteBoundary></section>
    <section className="page-section dart-pulse"><div className="section-head"><div><p className="eyebrow">DART PULSE</p><h2>Latest corporate filings</h2></div><a href={appHash('dart')}>View all filings →</a></div><RemoteBoundary state={filings} empty={(value) => !value.items.length}>{(value) => <div className="filing-list">{value.items.map((filing) => <FilingRow filing={filing} key={filing.receiptNumber} />)}</div>}</RemoteBoundary></section>
    <section className="page-section foreign-section"><div className="section-head"><div><p className="eyebrow">FOREIGN LIMIT MONITOR</p><h2>Acquisition limit signals</h2><p>Focused monitoring for four legally restricted stocks.</p></div><span className="policy-pill">Default warning ≥ 90%</span></div><RemoteBoundary state={limits} empty={(value) => !value.length}>{(value) => <div className="limit-grid">{value.map((monitor) => <ForeignCard key={monitor.stock.stockCode} monitor={monitor} />)}</div>}</RemoteBoundary></section>
    <section className="tax-banner page-section"><div><p className="eyebrow">TAX GUIDE</p><h2>Investing across borders?</h2><p>Compare treaty rates and verify required documents before broker submission.</p></div><a className="light-button" href={appHash('tax')}>Check your treaty tax eligibility →</a></section>
    <SiteFooter />
  </>
}

function DataLegend() { return <div className="data-legend"><span><i className="live-dot" /> Live</span><span><i className="delay-dot" /> Delayed</span><span>As-of time shown</span></div> }

function StockMiniCard({ stock }: { stock: Stock }) {
  const quote = stock.quote
  const profile = useProfile()
  const [watchlisted, setWatchlisted] = useState(stock.watchlisted)
  const toggle = async () => {
    if (!profile) { navigate('auth', undefined, { returnTo: appHash('stock-detail', stock.stockCode) }); return }
    const next = !watchlisted; setWatchlisted(next)
    try { await api(`/api/v1/me/watchlist/${stock.stockCode}`, { method: next ? 'PUT' : 'DELETE' }) }
    catch { setWatchlisted(!next) }
  }
  return <article className="stock-mini"><a href={appHash('stock-detail', stock.stockCode)}><div><span className="stock-avatar">{stock.nameEn?.slice(0, 1) || 'K'}</span><span><b>{stock.nameEn}</b><small>{stock.stockCode} · {stock.market}</small></span></div><strong>{formatNumber(quote?.currentPriceKrw, { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 })}</strong><em className={(quote?.changeRate || 0) >= 0 ? 'up' : 'down'}>{quote?.changeRate === null || quote?.changeRate === undefined ? quote?.status || 'UNAVAILABLE' : `${quote.changeRate >= 0 ? '+' : ''}${quote.changeRate.toFixed(2)}%`}</em></a><button className="heart" aria-label={`${watchlisted ? 'Remove from' : 'Add to'} watchlist`} onClick={() => void toggle()}>{watchlisted ? '♥' : '♡'}</button></article>
}

export function FilingRow({ filing }: { filing: Filing }) {
  return <a className="filing-row" href={appHash('filing-detail', filing.receiptNumber)}><time>{filing.filedDate}</time><span className="company-cell"><b>{filing.issuerNameEn || filing.issuerNameKo}</b><small>{filing.stockCode} · {filing.market}</small></span><span className="filing-cell"><b>{filing.titleEn || filing.titleKo}</b><small>{filing.type} · {filing.indexStatus}</small></span>{filing.correction && <em>Correction</em>}<i>→</i></a>
}

function ForeignCard({ monitor }: { monitor: ForeignMonitor }) {
  const ownership = monitor.stock.foreignOwnership
  const rate = ownership?.limitExhaustionRate
  return <a className={`limit-card ${monitor.warning ? 'warning' : ''}`} href={appHash('stock-detail', monitor.stock.stockCode)}><div><span className="stock-avatar">{monitor.stock.nameEn.slice(0, 1)}</span><span><b>{monitor.stock.nameEn}</b><small>{monitor.stock.stockCode}</small></span>{monitor.warning && <em>⚠ Near limit</em>}</div><div className="gauge" style={{ '--gauge': `${Math.min(rate || 0, 100)}%` } as React.CSSProperties}><span><b>{rate === null || rate === undefined ? 'N/A' : `${rate.toFixed(1)}%`}</b><small>Limit used</small></span></div><dl><div><dt>Ownership</dt><dd>{ownership?.ownershipRate === null || ownership?.ownershipRate === undefined ? 'Unavailable' : `${ownership.ownershipRate.toFixed(1)}%`}</dd></div><div><dt>Available shares</dt><dd>{formatNumber(ownership?.availableQuantity, { notation: 'compact' })}</dd></div></dl><small>As of {formatDate(ownership?.collectedAt)}</small></a>
}

export function ScreenerPage() {
  const [market, setMarket] = useState('')
  const [sort, setSort] = useState('STOCK_CODE')
  const [caution, setCaution] = useState(false)
  const [watchlist, setWatchlist] = useState(false)
  const profile = useProfile()
  const state = useRemote(() => api<{ count: number; items: Stock[] }>(`/api/v1/market/stocks${queryString({ market, sort, tradingCaution: caution || null, watchlist: watchlist || null, limit: 75 })}`), [market, sort, caution, watchlist, profile])
  return <div className="content-page"><PageTitle eyebrow="REAL-TIME STOCK SCREENER" title="Explore the supported market" copy="Filter 75 supported common stocks. Unavailable source data remains clearly unavailable." />
    <div className="filter-bar"><label>Market<select value={market} onChange={(event) => setMarket(event.target.value)}><option value="">All markets</option><option>KOSPI</option><option>KOSDAQ</option></select></label><label>Sort<select value={sort} onChange={(event) => setSort(event.target.value)}><option value="STOCK_CODE">Stock code</option><option value="CHANGE_DESC">Top gainers</option><option value="CHANGE_ASC">Top decliners</option><option value="VOLUME_DESC">Volume</option></select></label><label className="check"><input type="checkbox" checked={caution} onChange={(event) => setCaution(event.target.checked)} /> Trading caution</label><label className="check"><input type="checkbox" checked={watchlist} disabled={!profile} onChange={(event) => setWatchlist(event.target.checked)} /> My watchlist</label></div>
    <RemoteBoundary state={state} empty={(value) => !value.items.length}>{(value) => <div className="stock-table"><div className="table-header"><span>Company</span><span>Price</span><span>Change</span><span>Volume</span><span>Status</span><span>Foreign limit</span></div>{value.items.map((stock) => <StockTableRow key={stock.stockCode} stock={stock} />)}</div>}</RemoteBoundary>
  </div>
}

function StockTableRow({ stock }: { stock: Stock }) {
  const quote = stock.quote
  const ownership = stock.foreignOwnership
  const warnings = [quote?.viActive && 'VI', quote?.singlePriceTrading && 'Single price', quote?.tradingHalted && 'Halted', quote?.priceLimitState && quote.priceLimitState !== 'NONE' && quote.priceLimitState].filter(Boolean)
  return <a className="stock-table-row" href={appHash('stock-detail', stock.stockCode)}><span className="company-cell"><span className="stock-avatar">{stock.nameEn?.slice(0, 1)}</span><span><b>{stock.nameEn || stock.nameKo}</b><small>{stock.nameKo} · {stock.stockCode} · {stock.market}</small></span></span><strong>{formatNumber(quote?.currentPriceKrw, { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 })}</strong><em className={(quote?.changeRate || 0) >= 0 ? 'up' : 'down'}>{quote?.changeRate === null || quote?.changeRate === undefined ? 'Unavailable' : `${quote.changeRate >= 0 ? '+' : ''}${quote.changeRate.toFixed(2)}%`}</em><span>{formatNumber(quote?.volume, { notation: 'compact' })}</span><span>{warnings.length ? warnings.map(String).join(' · ') : quote?.status || 'Unavailable'}</span><span>{ownership?.status === 'AVAILABLE' ? `${ownership.limitExhaustionRate?.toFixed(1) || 'N/A'}%` : 'Not available'}</span></a>
}

export function SearchPage({ query }: { query: string }) {
  const [tab, setTab] = useState<'all' | 'stocks' | 'filings' | 'news'>('all')
  const [sort, setSort] = useState<'RELEVANCE' | 'LATEST'>('RELEVANCE')
  const [stockCode, setStockCode] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [filingType, setFilingType] = useState('')
  const [sentiment, setSentiment] = useState('')
  const [importance, setImportance] = useState('')
  const stocks = useRemote(() => query ? api<{ items: Stock[] }>(`/api/v1/market/stocks/search${queryString({ query, limit: 20 })}`) : Promise.resolve({ items: [] }), [query])
  const filings = useRemote(() => query ? api<FilingPage>(`/api/v1/disclosures${queryString({ query, stockCode, from, to, types: filingType, limit: 20 })}`) : Promise.resolve({ items: [], nextCursor: null }), [query, stockCode, from, to, filingType])
  const news = useRemote(() => query ? api<NewsPage>(`/api/v1/news${queryString({ query, stockCode, sentiment, importance, from: from ? `${from}T00:00:00Z` : null, to: to ? `${to}T23:59:59Z` : null, sort: sort === 'LATEST' ? 'LATEST' : 'IMPORTANCE', limit: 20 })}`) : Promise.resolve({ items: [], nextCursor: null }), [query, stockCode, sentiment, importance, from, to, sort])
  const filingItems = useMemo(() => [...(filings.data?.items || [])].sort((a, b) => sort === 'LATEST' ? b.filedDate.localeCompare(a.filedDate) : 0), [filings.data, sort])
  return <div className="content-page"><PageTitle eyebrow="INTEGRATED SEARCH" title={`Search results for “${query}”`} copy="Stocks, filings and news are searched across the full supported dataset." /><div className="search-toolbar"><div className="tabs" role="tablist">{(['all', 'stocks', 'filings', 'news'] as const).map((item) => <button role="tab" aria-selected={tab === item} className={tab === item ? 'active' : ''} onClick={() => setTab(item)} key={item}>{item[0].toUpperCase() + item.slice(1)}</button>)}</div><label>Sort<select value={sort} onChange={(event) => setSort(event.target.value as 'RELEVANCE' | 'LATEST')}><option value="RELEVANCE">Relevance</option><option value="LATEST">Latest</option></select></label></div>
    <div className="filter-bar"><label>Stock code<input value={stockCode} maxLength={6} pattern="[0-9A-Za-z]{6}" onChange={(event) => setStockCode(event.target.value.toUpperCase())} placeholder="All stocks" /></label><label>From<input type="date" value={from} max={to || undefined} onChange={(event) => setFrom(event.target.value)} /></label><label>To<input type="date" value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} /></label><label>Filing type<select value={filingType} onChange={(event) => setFilingType(event.target.value)}><option value="">All types</option><option>PERIODIC</option><option>MATERIAL_EVENT</option><option>ISSUANCE</option><option>OWNERSHIP</option><option>OTHER</option></select></label><label>News sentiment<select value={sentiment} onChange={(event) => setSentiment(event.target.value)}><option value="">All</option><option>POSITIVE</option><option>NEUTRAL</option><option>NEGATIVE</option><option>MIXED</option></select></label><label>News importance<select value={importance} onChange={(event) => setImportance(event.target.value)}><option value="">All</option><option>CRITICAL</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label></div>
    {(tab === 'all' || tab === 'stocks') && <section className="result-section"><div className="split-heading"><h2>Stocks</h2><span>{stocks.data?.items.length || 0} matches</span></div><RemoteBoundary state={stocks} empty={(value) => !value.items.length}>{(value) => <div className="stock-strip">{value.items.map((stock) => <StockMiniCard stock={stock} key={stock.stockCode} />)}</div>}</RemoteBoundary></section>}
    {(tab === 'all' || tab === 'filings') && <section className="result-section"><div className="split-heading"><h2>Filings</h2><span>{filingItems.length} matches</span></div><RemoteBoundary state={filings} empty={(value) => !value.items.length}>{() => <div className="filing-list">{filingItems.map((filing) => <FilingRow filing={filing} key={filing.receiptNumber} />)}</div>}</RemoteBoundary></section>}
    {(tab === 'all' || tab === 'news') && <section className="result-section"><div className="split-heading"><h2>News</h2><span>{news.data?.items.length || 0} matches</span></div><RemoteBoundary state={news} empty={(value) => !value.items.length}>{(value) => <div className="news-grid light">{value.items.map((article) => <NewsCard article={article} key={article.id} />)}</div>}</RemoteBoundary></section>}
  </div>
}

export function StockPage({ stockCode }: { stockCode: string }) {
  const profile = useProfile()
  const [range, setRange] = useState<'1D' | '1W' | '1M' | '3M' | '1Y'>('3M')
  const [cautionAcknowledged, setCautionAcknowledged] = useState(false)
  const historyLimit = { '1D': 2, '1W': 5, '1M': 22, '3M': 66, '1Y': 252 }[range]
  const detail = useRemote(() => api<StockDetail>(`/api/v1/market/stocks/${stockCode}`), [stockCode, profile])
  const history = useRemote(() => api<{ status: string; items: Array<{ tradingDate: string; closePriceKrw: number }> }>(`/api/v1/market/stocks/${stockCode}/history?limit=${historyLimit}`), [stockCode, historyLimit])
  const peers = useRemote(() => api<GlobalPeer>(`/api/v1/market/stocks/${stockCode}/global-peers`), [stockCode])
  const stockNews = useRemote(() => api<NewsPage>(`/api/v1/news${queryString({ stockCode, limit: 4 })}`), [stockCode])
  const stockFilings = useRemote(() => api<FilingPage>(`/api/v1/disclosures${queryString({ stockCode, limit: 5 })}`), [stockCode])
  const [tab, setTab] = useState('overview')
  const toggle = async (stock: StockDetail) => {
    if (!profile) { navigate('auth', undefined, { returnTo: appHash('stock-detail', stockCode) }); return }
    const next = !stock.watchlisted; detail.setData({ ...stock, watchlisted: next })
    try { await api(`/api/v1/me/watchlist/${stockCode}`, { method: next ? 'PUT' : 'DELETE' }) } catch { detail.setData(stock) }
  }
  return <div className="content-page stock-page"><RemoteBoundary state={detail}>{(stock) => <>
    {!cautionAcknowledged && hasTradingCaution(stock) && <TradingCautionModal stock={stock} close={() => setCautionAcknowledged(true)} />}
    {hasTradingCaution(stock) && <div className="trade-alert" role="alert"><b>⚠ Trading caution</b><span>{tradingCautions(stock).join(' · ')}</span><small>Source {stock.quote.source} · {formatDate(stock.quote.asOf)}</small></div>}
    <header className="stock-hero"><div className="stock-identity"><span className="stock-avatar large">{stock.nameEn.slice(0, 1)}</span><div><span>{stock.market} · {stock.sector || 'Sector unavailable'}</span><h1>{stock.nameEn || stock.nameKo}</h1><p>{stock.nameKo} · {stock.stockCode}</p></div><div className="stock-actions"><button className="watch-button" onClick={() => void toggle(stock)}>{stock.watchlisted ? '♥ Watchlisted' : '♡ Add to watchlist'}</button><button onClick={() => setTab('global peers')}>Explore Global Peers →</button><button onClick={() => openAgent('Explain the current stock context using the latest server data and cite the sources.')}>✦ Ask AI</button></div></div><div className="quote-block"><span>Current price</span><b>{formatNumber(stock.quote.currentPriceKrw, { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 })}</b><em className={(stock.quote.changeRate || 0) >= 0 ? 'up' : 'down'}>{stock.quote.changeRate === null ? stock.quote.status : `${stock.quote.changeRate >= 0 ? '+' : ''}${stock.quote.changeRate.toFixed(2)}%`}</em><small>{formatNumber(stock.currentPriceUsd, { style: 'currency', currency: 'USD' })} · FX {formatNumber(stock.exchangeRate.krwPerUnit)} KRW/USD</small><dl><div><dt>High</dt><dd>{formatNumber(stock.quote.highPriceKrw)}</dd></div><div><dt>Low</dt><dd>{formatNumber(stock.quote.lowPriceKrw)}</dd></div><div><dt>Volume</dt><dd>{formatNumber(stock.quote.volume, { notation: 'compact' })}</dd></div></dl><small>{stock.quote.source} · {formatDate(stock.quote.asOf)}</small></div></header>
    <div className="market-badges">{stock.quote.viActive && <span>VI Active</span>}{stock.quote.singlePriceTrading && <span>Single-price trading</span>}{stock.quote.priceLimitState && stock.quote.priceLimitState !== 'NONE' && <span>{stock.quote.priceLimitState}</span>}{stock.quote.tradingHalted && <span>Trading halted</span>}{isDelayed(stock) && <span>Data {stock.quote.status}</span>}</div>
    <div className="tabs">{['overview', 'news', 'filings', 'global peers'].map((item) => <button className={tab === item ? 'active' : ''} onClick={() => setTab(item)} key={item}>{item.replace(/^./, (value) => value.toUpperCase())}</button>)}</div>
    {tab === 'overview' && <div className="stock-dashboard"><section className="chart-card"><div className="split-heading"><div><h2>Price history</h2><small>Daily close · {historyLimit} observations max</small></div><div className="range-tabs">{(['1D', '1W', '1M', '3M', '1Y'] as const).map((item) => <button className={range === item ? 'active' : ''} onClick={() => setRange(item)} key={item}>{item}</button>)}</div></div><RemoteBoundary state={history} empty={(value) => !value.items.length}>{(value) => <PriceChart values={value.items} />}</RemoteBoundary></section><section className="ownership-card"><div className="split-heading"><h2>Foreign ownership</h2><span>{stock.subjectToForeignAcquisitionLimit ? 'Legal limit applies' : 'No acquisition limit'}</span></div>{stock.foreignOwnership.status === 'AVAILABLE' ? <><div className="metric-pair"><div><span>Ownership rate</span><b>{stock.foreignOwnership.ownershipRate?.toFixed(2)}%</b></div><div><span>Limit exhaustion</span><b>{stock.foreignOwnership.limitExhaustionRate?.toFixed(2)}%</b></div></div>{stock.subjectToForeignAcquisitionLimit && stock.foreignLimitPrediction.status === 'AVAILABLE' ? <PredictionBand prediction={stock.foreignLimitPrediction} threshold={stock.foreignLimitPolicy?.warningThreshold} /> : <StatusPanel title={stock.subjectToForeignAcquisitionLimit ? 'Prediction unavailable' : 'Not subject to foreign acquisition limit'} message={stock.subjectToForeignAcquisitionLimit ? 'More daily observations are required.' : 'This stock is outside the four-stock legal-limit monitor.'} />}</> : <StatusPanel title="Foreign ownership unavailable" message="The latest KRX snapshot has not been loaded. A zero value is not assumed." />}</section></div>}
    {tab === 'news' && <RemoteBoundary state={stockNews} empty={(value) => !value.items.length}>{(value) => <div className="news-grid light">{value.items.map((article) => <NewsCard article={article} key={article.id} />)}</div>}</RemoteBoundary>}
    {tab === 'filings' && <RemoteBoundary state={stockFilings} empty={(value) => !value.items.length}>{(value) => <div className="filing-list">{value.items.map((filing) => <FilingRow filing={filing} key={filing.receiptNumber} />)}</div>}</RemoteBoundary>}
    {(tab === 'global peers' || tab === 'overview') && <section className="peer-section"><div className="section-head"><div><p className="eyebrow">GLOBAL PEER ANALYSIS</p><h2>Business and financial similarity</h2></div></div><RemoteBoundary state={peers}>{(value) => <GlobalPeerPanel value={value} />}</RemoteBoundary></section>}
  </>}</RemoteBoundary></div>
}

function tradingCautions(stock: StockDetail): string[] {
  return [
    stock.quote.tradingHalted && (stock.quote.tradingHaltReason || 'Trading halted'),
    stock.quote.viActive && 'Volatility interruption active',
    stock.quote.singlePriceTrading && 'Single-price trading',
    stock.quote.priceLimitState && stock.quote.priceLimitState !== 'NONE' && `Price limit: ${stock.quote.priceLimitState}`,
    isDelayed(stock) && `Market data status: ${stock.quote.status}`,
  ].filter((value): value is string => Boolean(value))
}

function isDelayed(stock: StockDetail): boolean {
  return !['LIVE', 'CLOSED', 'AVAILABLE'].includes(stock.quote.status)
}

function hasTradingCaution(stock: StockDetail): boolean {
  return tradingCautions(stock).length > 0
}

function TradingCautionModal({ stock, close }: { stock: StockDetail; close: () => void }) {
  return <div className="modal-backdrop" role="presentation"><section className="caution-modal" role="dialog" aria-modal="true" aria-labelledby="caution-title"><p className="eyebrow">TRADING CAUTION</p><h2 id="caution-title">Confirm current market conditions</h2><p>{stock.nameEn} · {stock.stockCode}</p><ul>{tradingCautions(stock).map((item) => <li key={item}>{item}</li>)}</ul><dl><div><dt>Detected</dt><dd>{formatDate(stock.quote.asOf)}</dd></div><div><dt>Current status</dt><dd>{stock.quote.status}</dd></div><div><dt>Source</dt><dd>{stock.quote.source}</dd></div></dl><p className="modal-disclaimer">This is pre-trade information only. Confirm actual order availability with your broker.</p><div><button className="primary" onClick={close}>I Understand</button><button onClick={() => { close(); document.querySelector('.market-badges')?.scrollIntoView({ behavior: 'smooth' }) }}>View Details</button></div></section></div>
}

function PriceChart({ values }: { values: Array<{ tradingDate: string; closePriceKrw: number }> }) {
  const width = 820; const height = 250; const prices = values.map((value) => value.closePriceKrw); const min = Math.min(...prices); const max = Math.max(...prices); const spread = max - min || 1
  const points = values.map((value, index) => `${(index / Math.max(values.length - 1, 1)) * width},${height - ((value.closePriceKrw - min) / spread) * (height - 24) - 12}`).join(' ')
  return <div className="price-chart"><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Daily close price chart"><defs><linearGradient id="chartFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="#23d5ab" stopOpacity=".34" /><stop offset="1" stopColor="#23d5ab" stopOpacity="0" /></linearGradient></defs><polygon points={`0,${height} ${points} ${width},${height}`} fill="url(#chartFill)" /><polyline points={points} fill="none" stroke="#0eaf8b" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" /></svg><div><span>{values[0]?.tradingDate}</span><b>{formatNumber(values.at(-1)?.closePriceKrw, { style: 'currency', currency: 'KRW', maximumFractionDigits: 0 })}</b><span>{values.at(-1)?.tradingDate}</span></div></div>
}

function PredictionBand({ prediction, threshold }: { prediction: StockDetail['foreignLimitPrediction']; threshold?: number }) {
  return <div className="prediction-band"><div><span>Expected range</span><b>{prediction.minRate?.toFixed(2)}% — {prediction.maxRate?.toFixed(2)}%</b></div><div className="band-track"><i style={{ left: `${prediction.minRate || 0}%`, width: `${(prediction.maxRate || 0) - (prediction.minRate || 0)}%` }} /><b style={{ left: `${prediction.baseRate || 0}%` }} /><em style={{ left: `${threshold || 90}%` }} /></div><small>Base {prediction.baseRate?.toFixed(2)}% · Confidence {prediction.confidence?.toFixed(2)} · {prediction.modelVersion}</small></div>
}

function GlobalPeerPanel({ value }: { value: GlobalPeer }) {
  return <div className="peer-panel"><header><div><span>{value.confidenceLevel} CONFIDENCE · {(value.confidenceScore * 100).toFixed(1)}%</span><h3>{value.headline}</h3><p>{value.summary}</p></div><div className="primary-peer"><small>Representative peer</small><b>{value.primaryPeer.ticker}</b><span>{value.primaryPeer.companyName}</span></div></header><div className="peer-rank">{value.peers.map((peer) => <article key={peer.ticker}><span>#{peer.rank} · {peer.dimension.replaceAll('_', ' ')}</span><h4>{peer.companyName} <b>{peer.ticker}</b></h4><p>{peer.country} · {peer.exchange} · {peer.industry}</p><strong>{(peer.similarityScore * 100).toFixed(1)}% match</strong><dl><div><dt>Revenue</dt><dd>{formatNumber(peer.revenueUsd, { style: 'currency', currency: 'USD', notation: 'compact' })}</dd></div><div><dt>Market cap</dt><dd>{formatNumber(peer.marketCapUsd, { style: 'currency', currency: 'USD', notation: 'compact' })}</dd></div></dl></article>)}</div><div className="peer-lower"><div><h4>Comparison dimensions</h4>{value.comparisons.map((item) => <article key={item.dimension}><b>{item.dimension.replaceAll('_', ' ')}</b><p>{item.description}</p><span>{item.peer.ticker}</span></article>)}</div><div><h4>Distinct strengths</h4><div className="strength-grid">{value.keyStrengths.map((item) => <article key={item.iconKey}><span>✦</span><b>{item.title}</b><p>{item.description}</p></article>)}</div></div></div><footer>Financial data as of {value.financialDataAsOf} · {value.source} · {value.rankerModelVersion}</footer></div>
}

export function PageTitle({ eyebrow, title, copy }: { eyebrow: string; title: string; copy: string }) { return <header className="page-title"><p className="eyebrow">{eyebrow}</p><h1>{title}</h1><p>{copy}</p></header> }

export function SiteFooter() { return <footer className="site-footer"><div className="brand"><span>K</span><b>K-Market<small>Navigator</small></b></div><p>Data sources: KIS, KRX, OpenDART and Naver News. Source availability and timestamps are shown per item.</p><p>This service provides information only. It does not execute orders or provide investment, legal or tax advice.</p><span>© 2026 K-Market Navigator</span></footer> }

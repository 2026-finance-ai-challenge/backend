import { FormEvent, MouseEvent, useMemo, useRef, useState } from 'react'
import { api, queryString } from '../api'
import { formatDate, InsightCards, NewsCard, openAgent, RemoteBoundary, StatusPanel, useRemote } from '../components'
import { appHash } from '../routing'
import type { Filing, FilingDetail, NewsArticle } from '../types'
import { FilingRow, PageTitle } from './market'

type NewsPage = { items: NewsArticle[]; nextCursor: string | null }
type FilingPage = { items: Filing[]; nextCursor: string | null }
type TermExplanation = {
  selectedText: string
  normalizedTerm: string
  definition: string
  contextualMeaning: string
  sources: Array<{ id: string; title: string; sourceName: string; sourceUrl: string }>
  confidence: number
  reviewRequired: boolean
  sufficientEvidence: boolean
  refusalReason: string | null
}
type FilingInsight = { what: string; why: string; impact: string; sourceSectionIds: string[]; sufficientEvidence: boolean; refusalReason: string | null; modelId: string; promptVersion: string; generatedAt: string }
type FilingAnswer = { answer: string; refused: boolean; refusalReason: string | null; citations: Array<{ id: string; sectionIds: string[]; heading: string; excerpt: string }>; model: string; promptVersion: string }

export function NewsPageView() {
  const [stockCode, setStockCode] = useState('')
  const [sentiment, setSentiment] = useState('')
  const [importance, setImportance] = useState('')
  const [marketImpact, setMarketImpact] = useState('')
  const [marketImpactImportance, setMarketImpactImportance] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [sort, setSort] = useState('LATEST')
  const [cursor, setCursor] = useState<string | null>(null)
  const state = useRemote(() => api<NewsPage>(`/api/v1/news${queryString({ stockCode, sentiment, importance, marketImpact, marketImpactImportance, from: from ? `${from}T00:00:00Z` : null, to: to ? `${to}T23:59:59Z` : null, sort, cursor, limit: 24 })}`), [stockCode, sentiment, importance, marketImpact, marketImpactImportance, from, to, sort, cursor])
  return <div className="content-page"><PageTitle eyebrow="NEWS INTELLIGENCE" title="Signals behind the headlines" copy="Sentiment and importance remain separate signals. AI summaries never replace the original source." />
    <div className="filter-bar"><label>Stock code<input value={stockCode} maxLength={6} onChange={(event) => { setStockCode(event.target.value.toUpperCase()); setCursor(null) }} placeholder="005930" /></label><label>Sentiment<select value={sentiment} onChange={(event) => { setSentiment(event.target.value); setCursor(null) }}><option value="">All</option><option>POSITIVE</option><option>NEUTRAL</option><option>NEGATIVE</option></select></label><label>Importance<select value={importance} onChange={(event) => { setImportance(event.target.value); setCursor(null) }}><option value="">All</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label><label>Impact direction<select value={marketImpact} onChange={(event) => { setMarketImpact(event.target.value); setCursor(null) }}><option value="">All</option><option>POSITIVE</option><option>NEUTRAL</option><option>NEGATIVE</option><option>UNCERTAIN</option></select></label><label>Impact level<select value={marketImpactImportance} onChange={(event) => { setMarketImpactImportance(event.target.value); setCursor(null) }}><option value="">All</option><option>CRITICAL</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select></label><label>From<input type="date" value={from} onChange={(event) => { setFrom(event.target.value); setCursor(null) }} /></label><label>To<input type="date" value={to} onChange={(event) => { setTo(event.target.value); setCursor(null) }} /></label><label>Sort<select value={sort} onChange={(event) => { setSort(event.target.value); setCursor(null) }}><option value="LATEST">Latest</option><option value="IMPORTANCE">Importance</option><option value="MARKET_IMPACT">Market impact</option></select></label></div>
    <RemoteBoundary state={state} empty={(value) => !value.items.length}>{(value) => <><div className="news-grid light">{value.items.map((article) => <NewsCard key={article.id} article={article} />)}</div><div className="pagination-actions"><button disabled={!cursor} onClick={() => setCursor(null)}>First page</button><button disabled={!value.nextCursor} onClick={() => setCursor(value.nextCursor)}>Load more →</button></div></>}</RemoteBoundary>
  </div>
}

export function NewsDetailPage({ articleId }: { articleId: string }) {
  const state = useRemote(() => api<NewsArticle>(`/api/v1/news/${articleId}`), [articleId])
  const [selection, setSelection] = useState<{ text: string; x: number; y: number } | null>(null)
  const [explanation, setExplanation] = useState<TermExplanation | null>(null)
  const [explaining, setExplaining] = useState(false)
  const [explainError, setExplainError] = useState('')
  const articleRef = useRef<HTMLDivElement>(null)

  const captureSelection = (event: MouseEvent) => {
    const selected = window.getSelection()?.toString().trim() || ''
    if (!selected || selected.length > 500 || !articleRef.current?.contains(window.getSelection()?.anchorNode || null)) { setSelection(null); return }
    const bounds = window.getSelection()?.getRangeAt(0).getBoundingClientRect()
    setSelection({ text: selected, x: Math.min(bounds?.left || event.clientX, window.innerWidth - 250), y: (bounds?.bottom || event.clientY) + window.scrollY + 8 })
  }
  const explain = async () => {
    if (!selection) return
    setExplaining(true); setExplainError('')
    try { setExplanation(await api<TermExplanation>(`/api/v1/news/${articleId}/term-explanations`, { method: 'POST', body: JSON.stringify({ selectedText: selection.text }) })); setSelection(null) }
    catch (reason) { setExplainError(reason instanceof Error ? reason.message : 'The selected term could not be explained.') }
    finally { setExplaining(false) }
  }
  return <div className="content-page article-page"><RemoteBoundary state={state}>{(article) => <>
    <a className="back-link" href={appHash('news')}>← Back to News</a><header className="article-head"><div className="news-tags"><span>{article.publisher}</span><span>{formatDate(article.publishedAt)}</span><span className={`sentiment ${(article.sentiment || '').toLowerCase()}`}>{article.sentiment || 'Pending'}</span><span>{article.importance || 'Unrated'} importance</span></div><h1>{article.englishTitle || article.originalTitle}</h1><div className="article-actions"><div className="stock-chips">{article.relatedStocks.map((stock) => <a key={stock.stockCode} href={appHash('stock-detail', stock.stockCode)}>{stock.nameEn || stock.nameKo} · {stock.stockCode}</a>)}</div><button className="primary" onClick={() => openAgent('Summarize this article and show the supporting sources.')}>✦ Ask AI about this article</button></div></header>
    <section className="article-insight"><div className="split-heading"><div><p className="eyebrow">AI INSIGHT</p><h2>What · Why · Impact</h2></div><span>{article.modelId || 'Analysis pending'} · {article.promptVersion || '—'}</span></div><InsightCards what={article.what} why={article.why} impact={article.impact} /></section>
    <div className="article-layout"><article className="article-body" ref={articleRef} onMouseUp={captureSelection}><div className="translation-label"><b>English translation</b><span>{article.analysisStatus} · paragraph structure preserved when available</span></div>{(article.englishBody || article.originalBody || article.originalExcerpt || '').split(/\n{2,}/).map((paragraph, index) => <p key={index}>{paragraph}</p>)}{!article.englishBody && <StatusPanel title="English translation unavailable" message="The original Korean text is shown. No synthetic translation was inserted." tone="warning" />}<a className="source-button" href={article.originalUrl} target="_blank" rel="noreferrer">Open original article ↗</a></article>
      <aside className="analysis-rail"><h3>Analysis signals</h3><dl><div><dt>Event</dt><dd>{article.eventType || 'Pending'}</dd></div><div><dt>Sentiment</dt><dd>{article.sentiment || 'Pending'}</dd></div><div><dt>Importance</dt><dd>{article.importance || 'Pending'}</dd></div><div><dt>Impact direction</dt><dd>{article.marketImpact || 'Pending'}</dd></div><div><dt>Impact level</dt><dd>{article.marketImpactImportance || 'Pending'}{article.marketImpactScore == null ? '' : ` · ${Math.round(article.marketImpactScore * 100)}%`}</dd></div></dl><p>Confidence values describe model certainty, not expected return.</p></aside></div>
    {selection && <div className="selection-popup" style={{ left: selection.x, top: selection.y }}><span>“{selection.text.slice(0, 48)}{selection.text.length > 48 ? '…' : ''}”</span><button onClick={() => void explain()} disabled={explaining}>✦ {explaining ? 'Explaining…' : 'Explain this term'}</button><button onClick={() => { openAgent(`Explain this selected article context without using facts outside the source: “${selection.text}”`); setSelection(null) }}>Ask about this context</button></div>}
    {(explanation || explainError) && <aside className="term-panel"><button className="panel-close" onClick={() => { setExplanation(null); setExplainError('') }}>×</button>{explainError ? <StatusPanel title="Explanation failed" message={explainError} tone="error" /> : explanation && <><p className="eyebrow">FINANCIAL TERM</p><h2>{explanation.normalizedTerm}</h2><small>Selected: {explanation.selectedText}</small>{explanation.sufficientEvidence ? <><section><b>Definition</b><p>{explanation.definition}</p></section><section><b>Meaning in this article</b><p>{explanation.contextualMeaning}</p></section><div className="confidence-row"><span>Confidence</span><b>{Math.round(explanation.confidence * 100)}%</b>{explanation.reviewRequired && <em>Review recommended</em>}</div><section><b>Evidence</b>{explanation.sources.map((source) => <a key={source.id} href={source.sourceUrl} target="_blank" rel="noreferrer">{source.title} · {source.sourceName} ↗</a>)}</section></> : <StatusPanel title="Insufficient evidence" message={explanation.refusalReason || 'The verified glossary and context did not support an explanation.'} />}</>}</aside>}
  </>}</RemoteBoundary></div>
}

export function DartPage() {
  const [stockCode, setStockCode] = useState('')
  const [type, setType] = useState('')
  const [correction, setCorrection] = useState('')
  const [range, setRange] = useState('1Y')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [cursor, setCursor] = useState<string | null>(null)
  const dates = useMemo(() => {
    if (range === 'CUSTOM') return { from: customFrom || null, to: customTo || null }
    const to = new Date(); const from = new Date(); const months = range === '1M' ? 1 : range === '3M' ? 3 : range === '6M' ? 6 : 12; from.setMonth(from.getMonth() - months)
    return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) }
  }, [customFrom, customTo, range])
  const state = useRemote(() => api<FilingPage>(`/api/v1/disclosures${queryString({ stockCode, types: type, correction, from: dates.from, to: dates.to, cursor, limit: 30 })}`), [stockCode, type, correction, dates.from, dates.to, cursor])
  return <div className="content-page"><PageTitle eyebrow="DART INTELLIGENCE" title="Corporate filings, grounded in source" copy="OpenDART filings for the 75-stock universe. Each filing is isolated by receipt number and document version." />
    <div className="scope-banner"><span>Data coverage</span><b>1999 onward</b><small>Recent one-year filings and five-year periodic reports are prioritized for indexing. Other filings support on-demand indexing.</small></div>
    <div className="filter-bar"><label>Stock code<input value={stockCode} onChange={(event) => { setStockCode(event.target.value.toUpperCase()); setCursor(null) }} maxLength={6} placeholder="All stocks" /></label><label>Date range<select value={range} onChange={(event) => { setRange(event.target.value); setCursor(null) }}><option>1M</option><option>3M</option><option>6M</option><option>1Y</option><option value="CUSTOM">Custom</option></select></label>{range === 'CUSTOM' && <><label>From<input type="date" value={customFrom} max={customTo || undefined} onChange={(event) => { setCustomFrom(event.target.value); setCursor(null) }} /></label><label>To<input type="date" value={customTo} min={customFrom || undefined} onChange={(event) => { setCustomTo(event.target.value); setCursor(null) }} /></label></>}<label>Filing type<select value={type} onChange={(event) => { setType(event.target.value); setCursor(null) }}><option value="">All types</option><option>PERIODIC</option><option>MATERIAL_EVENT</option><option>ISSUANCE</option><option>OWNERSHIP</option><option>OTHER</option></select></label><label>Correction<select value={correction} onChange={(event) => { setCorrection(event.target.value); setCursor(null) }}><option value="">All</option><option value="false">Original</option><option value="true">Correction</option></select></label></div>
    <div className="filing-table-head"><span>Filed</span><span>Company</span><span>Filing</span><span>AI status</span></div><RemoteBoundary state={state} empty={(value) => !value.items.length}>{(value) => <><div className="filing-list">{value.items.map((filing) => <FilingRow filing={filing} key={filing.receiptNumber} />)}</div><div className="pagination-actions"><button disabled={!cursor} onClick={() => setCursor(null)}>First page</button><button disabled={!value.nextCursor} onClick={() => setCursor(value.nextCursor)}>Load more →</button></div></>}</RemoteBoundary>
  </div>
}

export function FilingDetailPage({ receiptNumber }: { receiptNumber: string }) {
  const state = useRemote(() => api<FilingDetail>(`/api/v1/disclosures/${receiptNumber}`), [receiptNumber])
  const insight = useRemote(() => api<FilingInsight>(`/api/v1/disclosures/${receiptNumber}/insight`), [receiptNumber])
  const [selected, setSelected] = useState<{ id: string; text: string } | null>(null)
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<FilingAnswer | null>(null)
  const [asking, setAsking] = useState(false)
  const [askError, setAskError] = useState('')
  const [indexBusy, setIndexBusy] = useState(false)
  const [indexError, setIndexError] = useState('')
  const requestIndexing = async (filing: FilingDetail) => {
    if (['PENDING', 'PROCESSING'].includes(filing.indexStatus)) { state.retry(); return }
    setIndexBusy(true); setIndexError('')
    try {
      await api<void>(`/api/v1/disclosures/${receiptNumber}/index`, { method: 'POST' })
      state.setData({ ...filing, indexStatus: 'PENDING' })
    } catch (reason) { setIndexError(reason instanceof Error ? reason.message : 'Indexing could not be requested.') }
    finally { setIndexBusy(false) }
  }
  const ask = async (event: FormEvent) => {
    event.preventDefault(); if (!question.trim()) return
    if (state.data?.indexStatus !== 'READY') { setAskError('This filing must finish indexing before questions can be answered.'); return }
    setAsking(true); setAskError('')
    try { setAnswer(await api<FilingAnswer>(`/api/v1/disclosures/${receiptNumber}/questions`, { method: 'POST', body: JSON.stringify({ question: question.trim(), selectedContext: selected ? { sectionId: selected.id, text: selected.text.slice(0, 2000) } : null }) })) }
    catch (reason) { setAskError(reason instanceof Error ? reason.message : 'The filing question could not be answered.') }
    finally { setAsking(false) }
  }
  return <div className="filing-detail-page"><RemoteBoundary state={state}>{(filing) => {
    const sections = filing.documents.flatMap((document) => document.sections)
    return <><header className="filing-detail-head"><a href={appHash('dart')}>← DART Intelligence</a><div className="news-tags"><span>{filing.type}</span>{filing.correction && <span>Correction</span>}<span>{filing.documentStatus}</span><span>{filing.indexStatus}</span></div><h1>{filing.titleEn || filing.titleKo}</h1><p>{filing.issuerNameEn || filing.issuerNameKo} · {filing.stockCode} · {filing.market}</p><dl><div><dt>Receipt no.</dt><dd>{filing.receiptNumber}</dd></div><div><dt>Submitted by</dt><dd>{filing.submitter || 'Unavailable'}</dd></div><div><dt>Filed</dt><dd>{filing.filedDate}</dd></div><div><dt>Integrity</dt><dd>{filing.documents[0]?.contentHash ? 'SHA-256 verified' : 'Not available'}</dd></div></dl><a className="source-button" href={filing.officialUrl} target="_blank" rel="noreferrer">Open original DART filing ↗</a></header>
      {filing.indexStatus !== 'READY' && <section className="filing-index-state"><StatusPanel title={['PENDING', 'PROCESSING'].includes(filing.indexStatus) ? 'Filing indexing in progress' : 'Filing is not indexed'} message={indexError || (['PENDING', 'PROCESSING'].includes(filing.indexStatus) ? 'The source is being prepared for citation-grounded questions. Refresh to check progress.' : 'Request on-demand indexing to enable filing-scoped AI answers.')} tone={indexError ? 'error' : 'warning'} action={{ label: indexBusy ? 'Requesting…' : ['PENDING', 'PROCESSING'].includes(filing.indexStatus) ? 'Refresh status' : 'Request indexing', run: () => { if (!indexBusy) void requestIndexing(filing) } }} /></section>}
      <section className="filing-ai"><div className="split-heading"><div><p className="eyebrow">AI FILING INSIGHT</p><h2>Source-grounded summary</h2></div>{filing.indexStatus === 'READY' && insight.error && <button onClick={() => void api<FilingInsight>(`/api/v1/disclosures/${receiptNumber}/insight`, { method: 'POST' }).then(insight.setData)}>Generate insight</button>}</div>{filing.indexStatus !== 'READY' ? <StatusPanel title="Insight waits for indexing" message="No summary is generated until the filing source is ready." /> : insight.loading ? <InsightCards loading /> : insight.data?.sufficientEvidence ? <><InsightCards what={insight.data.what} why={insight.data.why} impact={insight.data.impact} /><small>Sources: {insight.data.sourceSectionIds.length} sections · {insight.data.modelId} · {formatDate(insight.data.generatedAt)}</small></> : <StatusPanel title="AI insight unavailable" message={insight.data?.refusalReason || insight.error?.message || 'No grounded summary has been generated.'} action={{ label: 'Generate', run: () => void api<FilingInsight>(`/api/v1/disclosures/${receiptNumber}/insight`, { method: 'POST' }).then(insight.setData) }} />}</section>
      <div className="filing-reader"><aside className="toc"><b>Table of contents</b>{sections.filter((section) => section.heading).map((section) => <a href={`#section-${section.id}`} key={section.id}>{section.heading}</a>)}<div className="version-box"><b>Document versions</b>{filing.versions.map((version) => <a className={version.current ? 'active' : ''} href={appHash('filing-detail', version.receiptNumber)} key={version.receiptNumber}>{version.filedDate}{version.correction && ' · Correction'}</a>)}</div></aside><article className="filing-body">{sections.map((section) => <section id={`section-${section.id}`} className={selected?.id === section.id ? 'selected' : ''} key={section.id}>{section.heading && <h2>{section.heading}</h2>}{section.kind === 'TABLE' ? <StructuredTable data={section.tableData} /> : <p>{section.text || 'Section text unavailable.'}</p>}<button disabled={filing.indexStatus !== 'READY'} onClick={() => setSelected({ id: section.id, text: section.text || JSON.stringify(section.tableData) })}>✦ Ask AI about this section</button></section>)}</article><aside className="filing-chat"><div className="context-chip"><b>FILING</b><span>{receiptNumber} · {selected ? 'Selected section' : 'Full filing'}</span></div>{selected && <div className="selected-context"><b>Search scope fixed</b><p>{selected.text.slice(0, 180)}…</p><button onClick={() => setSelected(null)}>Use full filing</button></div>}<form onSubmit={ask}><textarea value={question} onChange={(event) => setQuestion(event.target.value)} rows={4} maxLength={2000} disabled={filing.indexStatus !== 'READY'} placeholder={filing.indexStatus === 'READY' ? 'Ask a question using this filing only…' : 'Questions unlock after indexing completes.'} /><button disabled={asking || filing.indexStatus !== 'READY'}>{asking ? 'Checking evidence…' : 'Ask this filing'}</button></form>{askError && <StatusPanel title="Question failed" message={askError} tone="error" />}{answer && <article className="filing-answer">{answer.refused ? <StatusPanel title="Insufficient evidence" message={answer.refusalReason || 'The filing does not support this answer.'} /> : <><b>Answer</b><p>{answer.answer}</p>{answer.citations.map((citation) => <a href={`#section-${citation.sectionIds[0]}`} key={citation.id}>[{citation.id}] {citation.heading || 'Filing section'}<small>{citation.excerpt}</small></a>)}<small>{answer.model} · {answer.promptVersion}</small></>}</article>}<small>Answers are limited to retrieved filing evidence.</small></aside></div>
    </>
  }}</RemoteBoundary></div>
}

function StructuredTable({ data }: { data: unknown }) {
  if (!data || typeof data !== 'object') return <StatusPanel title="Table unavailable" message="The structured table payload could not be rendered." />
  const record = data as Record<string, unknown>
  const rows = Array.isArray(record.rows) ? record.rows : Array.isArray(data) ? data : []
  if (!rows.length) return <pre className="table-json">{JSON.stringify(data, null, 2)}</pre>
  const cells = rows.map((row) => Array.isArray(row) ? row : Object.values(row as Record<string, unknown>))
  return <div className="structured-table"><table><tbody>{cells.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => rowIndex === 0 ? <th key={cellIndex}>{String(cell ?? '')}</th> : <td key={cellIndex}>{String(cell ?? '')}</td>)}</tr>)}</tbody></table></div>
}

import { FormEvent, useState } from 'react'
import { api, login, session, signup } from '../api'
import { formatDate, formatNumber, RemoteBoundary, StatusPanel, useProfile, useRemote } from '../components'
import { appHash, navigate } from '../routing'
import type { Profile } from '../types'
import { PageTitle } from './market'

type Country = { countryCode: string; countryName: string }
type Eligibility = {
  countryCode: string
  countryName: string
  investorType: string
  treatyDataAvailable: boolean
  domesticDefaultRate: number
  treatyDividendRate: number | null
  potentialQualifyingCorporateRate: number | null
  minimumOwnershipPercent: number | null
  asOf: string
  sourceUrl: string | null
  domesticSourceUrl: string
  requiredDocuments: string[]
  caveats: string[]
}
type TaxDocument = {
  id: string
  documentType: string
  expectedResidencyCountry: string
  originalFileName: string
  mediaType: string
  sizeBytes: number
  status: string
  progress: number
  stage: string
  detectedDocumentType: string | null
  fields: Record<string, string | null> | null
  missingRequiredFields: string[]
  issues: Array<{ code: string; severity: string; message: string }>
  ocrConfidence: number | null
  tamperRisk: number | null
  manualReviewRequired: boolean
  modelId: string | null
  errorCode: string | null
  createdAt: string
  updatedAt: string
}

export function TaxPage() {
  const profile = useProfile()
  const countries = useRemote(() => api<Country[]>('/api/v1/tax/countries'), [])
  const [country, setCountry] = useState(profile?.nationality || 'US')
  const [investorType, setInvestorType] = useState<string>(profile?.investorType || 'INDIVIDUAL')
  const [result, setResult] = useState<Eligibility | null>(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState('')
  const documents = useRemote(() => profile ? api<TaxDocument[]>('/api/v1/me/tax-documents') : Promise.resolve([]), [profile])
  const check = async (event: FormEvent) => {
    event.preventDefault(); setChecking(true); setError('')
    try { setResult(await api<Eligibility>('/api/v1/tax/eligibility', { method: 'POST', body: JSON.stringify({ residencyCountry: country, investorType }) })) }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Treaty data could not be checked.') }
    finally { setChecking(false) }
  }
  const upload = async (documentType: string, file: File | undefined) => {
    if (!profile) { navigate('auth', undefined, { returnTo: appHash('tax') }); return }
    if (!file) return
    const data = new FormData(); data.set('documentType', documentType); data.set('expectedResidencyCountry', country); data.set('file', file)
    try { await api<TaxDocument>('/api/v1/me/tax-documents', { method: 'POST', body: data }); documents.retry() }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'The document upload failed.') }
  }
  return <div className="content-page tax-page"><PageTitle eyebrow="TAX GUIDE" title="Treaty information, without false certainty" copy="Compare published rates, prepare required documents and verify file completeness before broker or partner submission." />
    <div className="tax-steps"><span className="active">1 Eligibility</span><span>2 Documents</span><span>3 AI verification</span><span>4 Broker submission</span></div>
    <section className="eligibility-card"><form onSubmit={check}><div><p className="eyebrow">STEP 1</p><h2>Tax Eligibility Checker</h2><p>This result is general treaty information, not a binding eligibility decision.</p></div><label>Tax residency<select value={country} onChange={(event) => setCountry(event.target.value)}>{countries.data?.map((item) => <option value={item.countryCode} key={item.countryCode}>{item.countryName} ({item.countryCode})</option>)}</select></label><label>Investor type<select value={investorType} onChange={(event) => setInvestorType(event.target.value)}><option value="INDIVIDUAL">Individual</option><option value="CORPORATION">Corporation</option></select></label><button className="primary" disabled={checking}>{checking ? 'Checking published data…' : 'Compare rates'}</button></form>{error && <StatusPanel title="Tax guide request failed" message={error} tone="error" />}{result && <TaxResult result={result} />}</section>
    <section className="document-section"><div className="section-head"><div><p className="eyebrow">REQUIRED DOCUMENTS</p><h2>Prepare and verify</h2></div>{!profile && <a href={appHash('auth', undefined, { returnTo: appHash('tax') })}>Sign in to upload securely →</a>}</div><div className="document-grid"><DocumentCard title="Certificate of Tax Residence" type="RESIDENCY_CERTIFICATE" copy="Confirms tax residency for the relevant period." upload={upload} /><DocumentCard title="Apostille" type="APOSTILLE" copy="Authenticates the issuing authority where required." upload={upload} /><DocumentCard title="Reduced Withholding Application" type="REDUCED_TAX_APPLICATION" copy="Application data for broker or partner submission." upload={upload} /></div>
      {profile && <RemoteBoundary state={documents}>{(items) => items.length ? <div className="document-status-list">{items.map((document) => <TaxDocumentRow key={document.id} document={document} refresh={documents.retry} />)}</div> : <StatusPanel title="No tax documents uploaded" message="Choose a PDF or permitted image above. Files are validated and encrypted before storage." />}</RemoteBoundary>}
    </section>
    <section className="submission-guide"><p className="eyebrow">SUBMISSION GUIDE</p><h2>Prepare Documents <span>→</span> AI Verification <span>→</span> Submit to Broker/Partner</h2><p>AI verification is a completeness and consistency check. It is not approval by a tax authority or confirmation that a broker accepted the documents.</p></section>
  </div>
}

function TaxResult({ result }: { result: Eligibility }) {
  if (!result.treatyDataAvailable) return <StatusPanel title="Treaty data unavailable" message="No verified treaty entry exists for this country in the current operational dataset. The domestic default is shown without assuming a reduced rate." tone="warning" />
  return <div className="tax-result"><header><span>{result.countryName} · {result.investorType}</span><b>Data as of {result.asOf}</b></header><div className="rate-compare"><article><span>Korean domestic default</span><b>{result.domesticDefaultRate}%</b><small>Published domestic source</small></article><i>→</i><article className="highlight"><span>General treaty dividend rate</span><b>{result.treatyDividendRate}%</b><small>Conditions and beneficial ownership must be verified</small></article></div>{result.potentialQualifyingCorporateRate !== null && <p>Potential qualifying corporate rate: <b>{result.potentialQualifyingCorporateRate}%</b> with at least {result.minimumOwnershipPercent}% ownership, subject to treaty conditions.</p>}<div className="tax-caveats">{result.caveats.map((item) => <p key={item}>△ {item}</p>)}</div><div className="source-links"><a href={result.domesticSourceUrl} target="_blank" rel="noreferrer">Domestic source ↗</a>{result.sourceUrl && <a href={result.sourceUrl} target="_blank" rel="noreferrer">Treaty source ↗</a>}</div></div>
}

function DocumentCard({ title, type, copy, upload }: { title: string; type: string; copy: string; upload: (type: string, file?: File) => void }) {
  return <article className="document-card"><span>PDF · JPG · PNG</span><h3>{title}</h3><p>{copy}</p><label className="upload-button">Choose file<input type="file" accept="application/pdf,image/png,image/jpeg" onChange={(event) => void upload(type, event.target.files?.[0])} /></label><small>MIME type and file signature are verified.</small></article>
}

function TaxDocumentRow({ document, refresh }: { document: TaxDocument; refresh: () => void }) {
  const remove = async () => { if (!window.confirm('Remove this document from your account?')) return; await api(`/api/v1/me/tax-documents/${document.id}`, { method: 'DELETE' }); refresh() }
  const ongoing = ['PENDING', 'PROCESSING'].includes(document.status)
  return <article className={`document-status ${document.status.toLowerCase()}`}><div><span>{document.documentType}</span><b>{document.originalFileName}</b><small>{formatNumber(document.sizeBytes)} bytes · uploaded {formatDate(document.createdAt)}</small></div><div className="progress"><i style={{ width: `${document.progress}%` }} /><span>{document.progress}% · {document.stage}</span></div><strong>{document.status.replaceAll('_', ' ')}</strong>{document.ocrConfidence !== null && <small>OCR confidence {Math.round(document.ocrConfidence * 100)}% · tamper risk {Math.round((document.tamperRisk || 0) * 100)}%</small>}{document.missingRequiredFields.length > 0 && <p>Missing: {document.missingRequiredFields.join(', ')}</p>}{document.issues.map((issue) => <p key={issue.code}>{issue.severity}: {issue.message}</p>)}<div>{ongoing && <button onClick={refresh}>Refresh</button>}{document.status === 'FAILED' && <button onClick={() => void api(`/api/v1/me/tax-documents/${document.id}/retry`, { method: 'POST' }).then(refresh)}>Retry</button>}<button onClick={() => void remove()}>Remove</button></div></article>
}

export function AuthPage({ returnTo }: { returnTo?: string }) {
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [nationality, setNationality] = useState('US')
  const [investorType, setInvestorType] = useState('INDIVIDUAL')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError('')
    try {
      if (mode === 'signup') { await signup({ loginId, password, passwordConfirm, nationality, investorType }); setMode('login'); setPassword(''); setPasswordConfirm(''); return }
      await login(loginId, password)
      window.location.hash = returnTo?.startsWith('#/') ? returnTo : appHash('account')
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Authentication failed.') }
    finally { setBusy(false) }
  }
  return <div className="auth-screen"><section className="auth-brand"><a className="brand" href={appHash('home')}><span>K</span><b>K-Market<small>Navigator</small></b></a><div><p className="eyebrow">GLOBAL MARKET ACCESS</p><h1>Research Korea<br />with grounded<br />intelligence.</h1><p>Your watchlist, documents and chats are protected by server-side ownership checks.</p></div><small>Redis-backed JWT sessions · rotating refresh tokens · Argon2id passwords</small></section><section className="auth-form"><form onSubmit={submit}><p className="eyebrow">{mode === 'login' ? 'WELCOME BACK' : 'CREATE ACCOUNT'}</p><h2>{mode === 'login' ? 'Sign in to continue' : 'Join K-Market Navigator'}</h2><label>Login ID<input required minLength={4} maxLength={30} pattern="[A-Za-z0-9][A-Za-z0-9._-]{3,29}" autoComplete="username" value={loginId} onChange={(event) => setLoginId(event.target.value)} /></label><label>Password<input required type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={password} onChange={(event) => setPassword(event.target.value)} /></label>{mode === 'signup' && <><label>Confirm password<input required type="password" autoComplete="new-password" value={passwordConfirm} onChange={(event) => setPasswordConfirm(event.target.value)} /></label><div className="form-grid"><label>Nationality<input value={nationality} pattern="[A-Za-z]{2}" maxLength={2} onChange={(event) => setNationality(event.target.value.toUpperCase())} /></label><label>Investor type<select value={investorType} onChange={(event) => setInvestorType(event.target.value)}><option value="INDIVIDUAL">Individual</option><option value="CORPORATION">Corporation</option></select></label></div><p className="form-hint">Password: 12+ characters with upper/lowercase, number and symbol. By creating an account, you agree to the terms and privacy policy.</p></>}{error && <p className="form-error" role="alert">{error}</p>}<button className="primary full" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}</button><button type="button" className="text-button full" onClick={() => { setMode(mode === 'login' ? 'signup' : 'login'); setError('') }}>{mode === 'login' ? 'Need an account? Sign up' : 'Already have an account? Sign in'}</button></form></section></div>
}

type WatchItem = { stockCode: string; nameKo: string; nameEn: string; market: string; addedAt: string }
type RecentItem = { itemType: string; referenceId: string; stockCode: string | null; viewedAt: string }
type Room = { id: string; name: string; context: { type: string; title: string }; version: number; updatedAt: string; lastMessageAt: string | null }

export function AccountPage() {
  const profile = useProfile()
  if (!profile) return <div className="content-page"><StatusPanel title="Sign in required" message="Watchlists, tax documents, recently viewed items and chats are private account data." action={{ label: 'Sign in', run: () => navigate('auth', undefined, { returnTo: appHash('account') }) }} /></div>
  return <AccountDashboard profile={profile} />
}

function AccountDashboard({ profile }: { profile: Profile }) {
  const watchlist = useRemote(() => api<{ items: WatchItem[] }>('/api/v1/me/watchlist'), [profile])
  const recent = useRemote(() => api<RecentItem[]>('/api/v1/me/recently-viewed?limit=12'), [profile])
  const chats = useRemote(() => api<Room[]>('/api/v1/me/chats?limit=8'), [profile])
  const documents = useRemote(() => api<TaxDocument[]>('/api/v1/me/tax-documents'), [profile])
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [notice, setNotice] = useState('')
  const changePassword = async (event: FormEvent) => { event.preventDefault(); setNotice(''); try { await api('/api/v1/me/password', { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword, newPasswordConfirm: newPassword }) }); setNotice('Password changed. Other sessions were revoked.'); setCurrentPassword(''); setNewPassword('') } catch (reason) { setNotice(reason instanceof Error ? reason.message : 'Password change failed.') } }
  return <div className="content-page account-page"><PageTitle eyebrow="MY PAGE" title={`Welcome, ${profile.loginId}`} copy="Manage your personalized market research, AI chats and verified tax documents." />
    <section className="profile-summary"><div className="profile-avatar">{profile.loginId.slice(0, 1).toUpperCase()}</div><div><span>Login ID</span><b>{profile.loginId}</b></div><div><span>Nationality</span><b>{profile.nationality}</b></div><div><span>Investor type</span><b>{profile.investorType}</b></div><div><span>Tax verification</span><b>{profile.taxVerificationStatus}</b></div></section>
    <div className="account-grid"><section><div className="split-heading"><h2>My Watchlist</h2><a href={appHash('screener')}>Find stocks →</a></div><RemoteBoundary state={watchlist}>{(value) => value.items.length ? <div className="compact-list">{value.items.map((item) => <a href={appHash('stock-detail', item.stockCode)} key={item.stockCode}><span className="stock-avatar">{item.nameEn.slice(0, 1)}</span><span><b>{item.nameEn}</b><small>{item.nameKo} · {item.stockCode} · {item.market}</small></span><time>{formatDate(item.addedAt, true)}</time></a>)}</div> : <StatusPanel title="No watchlist stocks" message="Use the heart button in search results or stock detail." />}</RemoteBoundary></section>
      <section><div className="split-heading"><h2>Recently Viewed</h2></div><RemoteBoundary state={recent}>{(items) => items.length ? <div className="compact-list">{items.map((item) => <a href={item.itemType === 'STOCK' ? appHash('stock-detail', item.referenceId) : item.itemType === 'FILING' ? appHash('filing-detail', item.referenceId) : '#'} key={`${item.itemType}-${item.referenceId}`}><span className="recent-icon">{item.itemType.slice(0, 1)}</span><span><b>{item.itemType}</b><small>{item.referenceId}{item.stockCode && ` · ${item.stockCode}`}</small></span><time>{formatDate(item.viewedAt)}</time></a>)}</div> : <StatusPanel title="No recent research" message="Stocks, news and filings you open will appear here." />}</RemoteBoundary></section>
      <section><div className="split-heading"><h2>My Chats</h2><span>Managed in AI Agent</span></div><RemoteBoundary state={chats}>{(items) => items.length ? <div className="compact-list">{items.map((room) => <div key={room.id}><span className="recent-icon">AI</span><span><b>{room.name}</b><small>{room.context.type} · {room.context.title}</small></span><time>{formatDate(room.lastMessageAt)}</time></div>)}</div> : <StatusPanel title="No chat rooms" message="Open AI Agent from any screen to start a context-bound conversation." />}</RemoteBoundary></section>
      <section><div className="split-heading"><h2>Tax Documents</h2><a href={appHash('tax')}>Open Tax Guide →</a></div><RemoteBoundary state={documents}>{(items) => items.length ? <div className="compact-list">{items.map((document) => <div key={document.id}><span className="recent-icon">TX</span><span><b>{document.originalFileName}</b><small>{document.documentType} · {document.stage}</small></span><strong>{document.status}</strong></div>)}</div> : <StatusPanel title="No uploaded documents" message="Secure uploads and OCR verification are available in Tax Guide." />}</RemoteBoundary></section></div>
    <section className="security-panel"><div><p className="eyebrow">SECURITY</p><h2>Session and password</h2><p>Access and refresh tokens are held in memory only. Server-side Redis state enables token rotation and immediate revocation.</p><button onClick={() => void api('/api/v1/auth/logout-all', { method: 'POST' }).finally(() => { session.clear(); navigate('home') })}>Log out all sessions</button><button onClick={() => void session.logout().then(() => navigate('home'))}>Log out this session</button></div><form onSubmit={changePassword}><label>Current password<input type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} autoComplete="current-password" required /></label><label>New password<input type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} autoComplete="new-password" required /></label><button className="primary">Change password</button>{notice && <p className="form-hint">{notice}</p>}</form></section>
  </div>
}

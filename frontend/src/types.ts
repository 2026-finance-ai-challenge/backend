export type ApiProblem = { code?: string; message?: string; status?: number; retryAfter?: string }

export type InvestorType = 'INDIVIDUAL' | 'CORPORATE'

export type Profile = {
  id: string
  loginId: string
  nationality: string
  investorType: InvestorType
  taxVerificationStatus: string
  createdAt: string
}

export type TokenPair = {
  accessToken: string
  refreshToken: string
  accessTokenExpiresAt: string
  refreshTokenExpiresAt: string
  user: Profile
}

export type Quote = {
  status: string
  currentPriceKrw: number | null
  changeAmountKrw: number | null
  changeRate: number | null
  openPriceKrw: number | null
  highPriceKrw: number | null
  lowPriceKrw: number | null
  volume: number | null
  marketSession: string | null
  viActive: boolean | null
  singlePriceTrading: boolean | null
  priceLimitState: string | null
  tradingHalted: boolean | null
  tradingHaltReason: string | null
  tradingStatusAvailable: boolean
  asOf: string | null
  source: string
}

export type ForeignOwnership = {
  status: string
  foreignOwnedQuantity: number | null
  totalListedQuantity: number | null
  foreignLimitQuantity: number | null
  availableQuantity: number | null
  ownershipRate: number | null
  limitExhaustionRate: number | null
  baseDate: string | null
  collectedAt: string | null
  source: string
}

export type Stock = {
  stockCode: string
  nameKo: string
  nameEn: string
  market: string
  sector: string | null
  watchlisted: boolean
  quote?: Quote
  foreignOwnership?: ForeignOwnership
}

export type StockDetail = Stock & {
  quote: Quote
  currentPriceUsd: number | null
  exchangeRate: { currency: string; krwPerUnit: number | null; status: string; asOf: string | null; source: string }
  foreignOwnership: ForeignOwnership
  subjectToForeignAcquisitionLimit: boolean
  foreignLimitPolicy: { warningThreshold: number; effectiveFrom: string } | null
  foreignLimitPrediction: {
    status: string
    minRate: number | null
    baseRate: number | null
    maxRate: number | null
    observationCount: number
    observationWindowDays: number
    confidence: number | null
    modelVersion: string | null
    baseDate: string | null
    calculatedAt: string | null
    source: string
  }
}

export type NewsArticle = {
  id: string
  clusterId: string | null
  originalTitle: string
  originalExcerpt: string | null
  originalBody: string | null
  englishTitle: string | null
  englishBody: string | null
  what: string | null
  why: string | null
  impact: string | null
  eventType: string | null
  sentiment: string | null
  importance: string | null
  marketImpact: string | null
  marketImpactImportance: string | null
  marketImpactScore: number | null
  confidence: Record<string, number | null>
  originalUrl: string
  publisher: string
  thumbnailUrl: string | null
  contentAvailability: string
  analysisStatus: string
  modelId: string | null
  promptVersion: string | null
  publishedAt: string
  relatedCoverageCount: number
  relatedStocks: Array<{ stockCode: string; nameKo: string; nameEn: string }>
}

export type TranslationResult = {
  jobId: string | null
  sourceHash: string
  targetLocale: 'en'
  translationVersion: string
  status: 'NOT_REQUESTED' | 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED'
  result: {
    translatedParagraphs?: string[]
    what?: string
    why?: string
    impact?: string
    contentAvailability?: string
    translatedHeading?: string | null
    translatedText?: string | null
    translatedTableData?: unknown
  } | null
  modelId: string | null
  promptVersion: string | null
  generatedAt: string | null
  errorCode: string | null
}

export type Filing = {
  receiptNumber: string
  issuerNameKo: string
  issuerNameEn: string
  stockCode: string
  market: string
  type: string
  titleKo: string
  titleEn: string | null
  filedDate: string
  detectedAt: string
  correction: boolean
  documentStatus: string
  indexStatus: string
  officialUrl: string
}

export type FilingDetail = Filing & {
  submitter: string | null
  remark: string | null
  documents: Array<{
    id: string
    sourceFilename: string
    version: number
    contentHash: string
    sections: Array<{ id: string; ordinal: number; kind: string; heading: string | null; text: string | null; tableData: unknown }>
  }>
  versions: Array<{ receiptNumber: string; titleKo: string; filedDate: string; correction: boolean; current: boolean }>
}

export type ChatRoom = {
  id: string
  name: string
  contextType: 'GENERAL' | 'STOCK' | 'NEWS' | 'FILING' | 'TAX_GUIDE'
  contextReferenceId: string | null
  contextVersion: string | null
  lastMessagePreview: string | null
  lastMessageAt: string | null
  updatedAt: string
}

export type ChatMessage = {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  status: string
  citations: Array<{ label?: string; referenceId?: string; url?: string }>
  createdAt: string
}

export type GlobalPeer = {
  stockCode: string
  stockNameEn: string
  headline: string
  summary: string
  primaryPeer: Peer
  peers: Peer[]
  comparisons: Array<{ dimension: string; description: string; peer: Peer }>
  keyStrengths: Array<{ title: string; description: string; iconKey: string }>
  confidenceScore: number
  confidenceLevel: string
  financialDataAsOf: string
  rankerModelVersion: string
  source: string
}

export type Peer = {
  dimension: string
  rank: number
  ticker: string
  companyName: string
  exchange: string
  country: string
  similarityScore: number
  sector: string
  industry: string
  fiscalYear: number | null
  marketCapUsd: number | null
  revenueUsd: number | null
  operatingIncomeUsd: number | null
  netIncomeUsd: number | null
  financialDataSource: string
}

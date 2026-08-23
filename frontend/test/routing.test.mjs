import assert from 'node:assert/strict'
import test from 'node:test'
import { appHash, parseAppLocation } from '../src/routing.ts'

test('상세 화면 식별자와 검색 조건을 URL에서 복원한다', () => {
  const stock = parseAppLocation('#/stock/005930?tab=filings')
  assert.equal(stock.route, 'stock-detail')
  assert.equal(stock.id, '005930')
  assert.equal(stock.query.get('tab'), 'filings')
  assert.equal(appHash('search', undefined, { q: 'Samsung' }), '#/search?q=Samsung')
})

test('지원하지 않는 경로와 잘못된 식별자는 홈으로 격리한다', () => {
  assert.equal(parseAppLocation('#/stock/123').route, 'home')
  assert.equal(parseAppLocation('#/admin').route, 'home')
})

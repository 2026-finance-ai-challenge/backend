import assert from 'node:assert/strict'
import test from 'node:test'
import { queryString } from '../src/api.ts'

test('API 검색 조건에서 비어 있는 값은 전송하지 않는다', () => {
  assert.equal(queryString({ stockCode: '005930', sentiment: '', limit: 20, watchlist: false }), '?stockCode=005930&limit=20&watchlist=false')
})

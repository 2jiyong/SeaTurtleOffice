import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, progressLabel, REQUEST_TIMEOUT_MS } from './api.ts'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('요청 타임아웃', () => {
  it('백엔드(28초)보다 조금 길게 기다린다', () => {
    expect(REQUEST_TIMEOUT_MS).toBe(30_000)
  })

  it('응답이 오지 않으면 30초 뒤 안내 문장과 함께 실패한다', async () => {
    vi.useFakeTimers()
    // 서버가 답하지 않는 fetch: 요청이 취소될 때만 끝난다.
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: string, init?: RequestInit) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
          }),
      ),
    )
    const result = api.ask('g1', '질문').catch((e: Error) => e)
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS)
    const error = await result
    expect((error as Error).message).toBe('응답이 너무 늦습니다. 다시 시도해 주세요.')
  })
})

describe('초기화 요청', () => {
  it('DELETE로 보내고 204면 본문 없이 끝난다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(api.reset('g1')).resolves.toBeUndefined()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/games/g1')
    expect(fetchMock.mock.calls[0][1].method).toBe('DELETE')
  })
})

describe('진행 상태 문구', () => {
  const base = { id: 'g1', scenario: 's', used: 12, limit: 40, score: 0 }
  it('진행 중은 사용 횟수, 해결은 점수, 실패·포기는 해결 못 함', () => {
    expect(progressLabel({ ...base, status: 'PLAYING' })).toBe('진행 중 (12/40)')
    expect(progressLabel({ ...base, status: 'SOLVED', score: 87 })).toBe('해결 · 87점')
    expect(progressLabel({ ...base, status: 'FAILED' })).toBe('해결 못 함')
    expect(progressLabel({ ...base, status: 'GAVE_UP' })).toBe('해결 못 함')
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, REQUEST_TIMEOUT_MS } from './api.ts'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('요청 타임아웃', () => {
  it('백엔드(45초)보다 조금 길게 기다린다', () => {
    expect(REQUEST_TIMEOUT_MS).toBe(50_000)
  })

  it('응답이 오지 않으면 50초 뒤 안내 문장과 함께 실패한다', async () => {
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

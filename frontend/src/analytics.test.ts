import { afterEach, describe, expect, it, vi } from 'vitest'
import { installUmami, pageFor, trackEvent, trackPage } from './analytics.ts'

afterEach(() => {
  delete window.umami
  document.head.querySelectorAll('script[data-website-id]').forEach((s) => s.remove())
})

describe('통계 주소 정규화', () => {
  it('목록·게임·결과 세 주소로만 묶는다(판 ID를 보내지 않는다)', () => {
    expect(pageFor('#/')).toBe('/')
    expect(pageFor('')).toBe('/')
    expect(pageFor('#/game/abc-1')).toBe('/game')
    expect(pageFor('#/game/abc-1/result')).toBe('/result')
  })
})

describe('기록 보내기', () => {
  it('페이지는 정규화한 주소로 기록한다', () => {
    const track = vi.fn()
    window.umami = { track }
    trackPage('#/game/0f1e-uuid')
    expect(track).toHaveBeenCalledTimes(1)
    const props = track.mock.calls[0][0] as (p: Record<string, unknown>) => Record<string, unknown>
    const sent = props({ url: '/', website: 'w', title: '바다거북 사무소' })
    expect(sent.url).toBe('/game')
    expect(sent.website).toBe('w')
    expect(JSON.stringify(sent)).not.toContain('0f1e-uuid')
  })

  it('사건 이벤트는 시나리오 slug만 담는다', () => {
    const track = vi.fn()
    window.umami = { track }
    trackEvent('case_open', 'old-lighthouse')
    expect(track).toHaveBeenCalledWith('case_open', { scenario: 'old-lighthouse' })
  })

  it('umami가 없으면(차단·미설정) 아무것도 하지 않는다', () => {
    expect(() => trackPage('#/')).not.toThrow()
    expect(() => trackEvent('case_reset', 's')).not.toThrow()
  })
})

describe('스크립트 넣기', () => {
  const scripts = () => document.head.querySelectorAll<HTMLScriptElement>('script[data-website-id]')

  it('주소나 사이트 ID가 비면 넣지 않는다', () => {
    installUmami(undefined, 'id')
    installUmami('https://stats.example/script.js', '')
    expect(scripts()).toHaveLength(0)
  })

  it('둘 다 있으면 자동 기록을 끈 스크립트 하나를 넣는다', () => {
    installUmami('https://stats.example/script.js', 'site-1')
    expect(scripts()).toHaveLength(1)
    const s = scripts()[0]
    expect(s.src).toBe('https://stats.example/script.js')
    expect(s.defer).toBe(true)
    expect(s.dataset.websiteId).toBe('site-1')
    expect(s.dataset.autoTrack).toBe('false')
    // 이벤트에 붙는 기본 주소에서 해시(#/game/<판 ID>)를 뺀다
    expect(s.dataset.excludeHash).toBe('true')
  })
})

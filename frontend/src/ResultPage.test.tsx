import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, type GameView } from './api.ts'
import ResultPage from './ResultPage.tsx'

vi.mock('./api.ts', async (importActual) => {
  const actual = await importActual<typeof import('./api.ts')>()
  return { ...actual, api: { game: vi.fn() } }
})
const { api } = await import('./api.ts')

const solved: GameView = {
  id: 'g1',
  scenario: { slug: 's', title: '사건', difficulty: 'normal', problem: '문제 문장', hintCount: 3, cover: null, keywordCount: 3, bonusCount: 1 },
  status: 'SOLVED',
  questions: [{ no: 1, seq: 1, text: '첫 질문', answer: 'YES' }],
  hints: [{ no: 1, seq: 2, text: '첫 계시', image: null }],
  guesses: [{ seq: 3, text: '추리', correct: true, closeness: 'CORRECT' }],
  used: 23,
  limit: 40,
  hintSteps: [10, 20, 30],
  finalChance: false,
  nextHintAt: null,
  reveal: {
    truth: '진상 문장',
    keywords: [
      { name: '청소부', description: '청소부가 쓸어 갔다.', found: true },
      { name: '월세', description: '월세를 못 냈다.', found: false },
    ],
    bonuses: [
      { name: '월세 체납', description: 'd1', found: true },
      { name: '증거가 남지 않음', description: 'd2', found: false },
    ],
    score: 87,
    bonusPoints: 10,
  },
}

async function openResult(overrides: Partial<GameView> = {}, handlers: { onExit?: () => void; onResume?: () => void } = {}) {
  vi.mocked(api.game).mockResolvedValue({ ...solved, ...overrides })
  render(<ResultPage gameId="g1" onExit={handlers.onExit ?? (() => {})} onResume={handlers.onResume ?? (() => {})} />)
}

beforeEach(() => {
  vi.clearAllMocks()
  Element.prototype.scrollTo = vi.fn() as unknown as typeof Element.prototype.scrollTo
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
})
afterEach(cleanup)

describe('결과 페이지 머리', () => {
  it('정답이면 사건 해결, 점수, 계산 줄', async () => {
    await openResult()
    expect(await screen.findByText('교신 종료 // 사건 기록 봉인')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '사건 해결' })).toBeTruthy()
    expect(screen.getByText('87점')).toBeTruthy()
    expect(screen.getByText('100 − 사용 23 + 보너스 10 = 87')).toBeTruthy()
  })

  it('실패와 포기는 미해결 종결과 보조 문구', async () => {
    const reveal = { ...solved.reveal!, score: 0, bonusPoints: 0 }
    await openResult({ status: 'FAILED', reveal })
    expect(await screen.findByRole('heading', { name: '미해결 종결' })).toBeTruthy()
    expect(screen.getByText('마지막 추리 실패')).toBeTruthy()
    expect(screen.getByText('0점')).toBeTruthy()
    cleanup()
    await openResult({ status: 'GAVE_UP', reveal })
    expect(await screen.findByText('조사 중단')).toBeTruthy()
  })

  it('들어오면 맨 위로 올리고 판정에 초점을 둔다', async () => {
    await openResult()
    const heading = await screen.findByRole('heading', { name: '사건 해결' })
    expect(window.scrollTo).toHaveBeenCalledWith(0, 0)
    expect(document.activeElement).toBe(heading)
  })

  it('목록으로 버튼은 onExit', async () => {
    const onExit = vi.fn()
    await openResult({}, { onExit })
    await userEvent.click(await screen.findByRole('button', { name: '목록으로' }))
    expect(onExit).toHaveBeenCalledTimes(1)
  })
})

describe('결과 페이지 탭', () => {
  it('처음에는 진상 탭: 진상, 핵심 키워드, 보너스', async () => {
    await openResult()
    const tab = await screen.findByRole('tab', { name: '진상' })
    expect(tab.getAttribute('aria-selected')).toBe('true')
    expect(screen.getByText('진상 문장')).toBeTruthy()
    expect(screen.getByText('청소부')).toBeTruthy()
    expect(screen.getByText('보너스 1/2')).toBeTruthy()
    expect(screen.getByText('맞힘')).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button', { name: '포기하고 진상 보기' })).toBeNull()
  })

  it('진상 탭은 정답 조건(핵심·보너스)을 먼저, 사건의 전말을 그다음에 보여 준다', async () => {
    await openResult()
    const conditions = await screen.findByRole('region', { name: '정답 조건' })
    const story = screen.getByRole('region', { name: '사건의 전말' })
    expect(conditions.compareDocumentPosition(story) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(conditions.textContent).toContain('핵심 01')
    expect(conditions.textContent).toContain('청소부')
    expect(conditions.textContent).toContain('놓침')
    expect(story.textContent).toContain('진상 문장')
  })

  it('추리로 맞힌 핵심과 보너스는 맞힘으로 표시한다', async () => {
    await openResult({ status: 'GAVE_UP', reveal: { ...solved.reveal!, score: 0 } })
    const conditions = await screen.findByRole('region', { name: '정답 조건' })
    const cards = conditions.querySelectorAll('.key-card')
    expect(cards[0].className).toContain('found')
    expect(cards[0].textContent).toContain('밝힘')
    expect(cards[1].className).not.toContain('found')
    expect(cards[1].textContent).toContain('놓침')
    expect(conditions.textContent).toContain('밝힌 핵심 1/2')
  })

  it('기록 탭은 사건 기록과 수신 기록을 함께, 읽기 전용으로', async () => {
    await openResult({ status: 'GAVE_UP', nextHintAt: 30, hints: [], reveal: { ...solved.reveal!, score: 0 } })
    await userEvent.click(await screen.findByRole('tab', { name: '기록' }))
    expect(screen.getByText('문제 문장')).toBeTruthy()
    expect(screen.getByText('첫 질문')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '수신 기록' })).toBeTruthy()
    expect(screen.queryByText('응시자와 연결됨')).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button', { name: /계시 \d 요청/ })).toBeNull()
  })

  it('기록 탭에서 열린 계시를 다시 본다', async () => {
    await openResult()
    await userEvent.click(await screen.findByRole('tab', { name: '기록' }))
    await userEvent.click(screen.getByRole('button', { name: '계시 1' }))
    expect(screen.getByRole('dialog', { name: '계시 1' })).toBeTruthy()
  })
})

describe('결과 페이지로 올 수 없는 게임', () => {
  it('진행 중인 게임이면 onResume', async () => {
    const onResume = vi.fn()
    await openResult({ status: 'PLAYING', reveal: null }, { onResume })
    await vi.waitFor(() => expect(onResume).toHaveBeenCalledTimes(1))
  })

  it('만료된 게임은 오류와 목록으로', async () => {
    vi.mocked(api.game).mockRejectedValue(new ApiError('없음', 404))
    render(<ResultPage gameId="g1" onExit={() => {}} onResume={() => {}} />)
    expect(await screen.findByText('게임이 만료되었거나 서버가 다시 시작되었습니다.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '목록으로' })).toBeTruthy()
  })
})

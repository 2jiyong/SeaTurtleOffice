import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ANSWER_TEXT, type GameView, type QuestionRecord } from './api.ts'
import GamePage from './GamePage.tsx'

// 네트워크(api)만 바꾼다. 응답 시점은 테스트가 정한다.
vi.mock('./api.ts', async (importActual) => {
  const actual = await importActual<typeof import('./api.ts')>()
  return {
    ...actual,
    api: { game: vi.fn(), ask: vi.fn(), guess: vi.fn(), hint: vi.fn(), giveUp: vi.fn() },
  }
})
const { api } = await import('./api.ts')

const game: GameView = {
  id: 'g1',
  scenario: { slug: 's', title: '사건', difficulty: 'normal', problem: '문제', hintCount: 3 },
  status: 'PLAYING',
  questions: [],
  hints: [],
  guesses: [],
  reveal: null,
}

/** 끝나지 않는 요청: 테스트 동안 '진행 중' 상태를 유지한다. */
const never = () => new Promise<never>(() => {})

async function openGame(overrides: Partial<GameView> = {}) {
  vi.mocked(api.game).mockResolvedValue({ ...game, ...overrides })
  render(<GamePage gameId="g1" onExit={() => {}} />)
  await screen.findByRole('button', { name: /힌트 받기/ })
}

beforeEach(() => {
  vi.clearAllMocks()
  Element.prototype.scrollIntoView = vi.fn()
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(cleanup)

describe('요청이 진행 중일 때 게임 상태를 바꾸는 버튼', () => {
  it('힌트를 받는 중에는 힌트를 다시 요청하지 않는다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.hint).mockImplementation(never)

    const hint = screen.getByRole('button', { name: /힌트 받기/ })
    await user.click(hint)
    await user.click(hint)

    expect(api.hint).toHaveBeenCalledTimes(1)
  })

  it('힌트를 받는 중에는 포기할 수 없다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.hint).mockImplementation(never)

    await user.click(screen.getByRole('button', { name: /힌트 받기/ }))
    await user.click(screen.getByRole('button', { name: /포기하고 진상 보기/ }))

    expect(api.giveUp).not.toHaveBeenCalled()
  })

  it('힌트를 받는 중에는 질문을 보내지 않는다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.hint).mockImplementation(never)

    await user.click(screen.getByRole('button', { name: /힌트 받기/ }))
    await user.type(screen.getByRole('textbox', { name: '질문' }), '건물주가 했나요?{Enter}')

    expect(api.ask).not.toHaveBeenCalled()
  })

  it('질문을 판정하는 중에는 힌트를 요청할 수 없다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.ask).mockImplementation(never as () => Promise<QuestionRecord>)

    await user.type(screen.getByRole('textbox', { name: '질문' }), '건물주가 했나요?{Enter}')
    await user.click(screen.getByRole('button', { name: /힌트 받기/ }))

    expect(api.ask).toHaveBeenCalledTimes(1)
    expect(api.hint).not.toHaveBeenCalled()
  })
})

describe('질문 기록', () => {
  it('진행자의 말과 그 아래 안내 문장을 보여 준다', async () => {
    await openGame({ questions: [{ no: 1, seq: 1, text: '건물주는 몇 살인가요?', answer: 'IRRELEVANT_UNDECIDED' }] })
    expect(screen.getByText('중요하지 않단다')).toBeTruthy()
    expect(screen.getByText('이 사건에서 정하지 않은 세부사항입니다.')).toBeTruthy()
  })

  it('말이 같은 두 코드는 같은 도장 색을 쓴다', async () => {
    await openGame({
      questions: [
        { no: 1, seq: 1, text: 'a', answer: 'PARTIAL_SOME' },
        { no: 2, seq: 2, text: 'b', answer: 'PARTIAL_CONDITION' },
      ],
    })
    const stamps = screen.getAllByText('어느정도는 그렇단다')
    expect(stamps[0].className).toBe(stamps[1].className)
    expect(stamps[0].className).toContain('s-partial')
  })

  it('증거 표시를 보여 주지 않는다', async () => {
    await openGame({ questions: [{ no: 1, seq: 1, text: 'a', answer: 'YES' }] })
    expect(screen.queryByText(/증거/)).toBeNull()
  })
})

it('ANSWER_TEXT가 9개 코드를 모두 담는다', () => {
  expect(Object.keys(ANSWER_TEXT)).toEqual([
    'YES',
    'NO',
    'PARTIAL_SOME',
    'PARTIAL_CONDITION',
    'IRRELEVANT_UNRELATED',
    'IRRELEVANT_UNDECIDED',
    'INVALID_FORM',
    'INVALID_TRUTH',
    'INVALID_RULE',
  ])
})

describe('추리 결과와 진상 공개', () => {
  it('오답 추리 아래에 빠졌을 때 문장을 보여 주고 보너스는 보여 주지 않는다', async () => {
    await openGame({
      guesses: [
        { seq: 1, text: 't', matched: 2, total: 3, correct: false, missing: ['건물주가 무엇을 바랐는지가 빠져 있구나.'] },
      ],
    })
    expect(screen.getByText('핵심 2/3')).toBeTruthy()
    expect(screen.getByText('건물주가 무엇을 바랐는지가 빠져 있구나.')).toBeTruthy()
    expect(screen.queryByText(/보너스/)).toBeNull()
  })

  it('진상 공개에서 핵심 키워드와 맞힌 보너스를 보여 준다', async () => {
    vi.mocked(api.game).mockResolvedValue({
      ...game,
      status: 'SOLVED',
      reveal: {
        truth: '진상',
        keywords: [{ name: '청소부', description: '청소부가 쓸어 갔다.' }],
        bonuses: [
          { name: '월세 체납', description: 'd1', found: true },
          { name: '증거가 남지 않음', description: 'd2', found: false },
        ],
      },
    })
    render(<GamePage gameId="g1" onExit={() => {}} />)
    expect(await screen.findByText('청소부')).toBeTruthy()
    expect(screen.getByText('보너스 1/2')).toBeTruthy()
    expect(screen.getByText('맞힘')).toBeTruthy()
  })
})

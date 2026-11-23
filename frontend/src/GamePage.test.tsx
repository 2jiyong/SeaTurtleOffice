import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GameView, QuestionRecord } from './api.ts'
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

async function openGame() {
  vi.mocked(api.game).mockResolvedValue(game)
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

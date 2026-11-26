import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ANSWER_TEXT, ApiError, type GameView } from './api.ts'
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
  scenario: { slug: 's', title: '사건', difficulty: 'normal', problem: '문제', hintCount: 3, cover: null },
  status: 'PLAYING',
  questions: [],
  hints: [],
  guesses: [],
  used: 0,
  limit: 40,
  hintSteps: [10, 20, 30],
  finalChance: false,
  nextHintAt: 10,
  reveal: null,
}

/** 끝나지 않는 요청: 테스트 동안 '진행 중' 상태를 유지한다. */
const never = () => new Promise<never>(() => {})

async function openGame(overrides: Partial<GameView> = {}) {
  vi.mocked(api.game).mockResolvedValue({ ...game, ...overrides })
  render(<GamePage gameId="g1" onExit={() => {}} onEnded={() => {}} />)
  await screen.findByRole('button', { name: '송신 ▶' })
}

beforeEach(() => {
  vi.clearAllMocks()
  Element.prototype.scrollIntoView = vi.fn()
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  Element.prototype.scrollTo = vi.fn() as unknown as typeof Element.prototype.scrollTo
  vi.spyOn(window, 'confirm')
})
afterEach(cleanup)

describe('요청이 진행 중일 때 게임 상태를 바꾸는 버튼', () => {
  it('힌트를 받는 중에는 힌트를 다시 요청하지 않는다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.hint).mockImplementation(never)

    const hint = screen.getByRole('button', { name: /계시 \d 요청/ })
    await user.click(hint)
    await user.click(hint)

    expect(api.hint).toHaveBeenCalledTimes(1)
  })

  it('힌트를 받는 중에는 포기할 수 없다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.hint).mockImplementation(never)

    await user.click(screen.getByRole('button', { name: /계시 \d 요청/ }))
    await user.click(screen.getByRole('button', { name: /포기하고 진상 보기/ }))

    expect(api.giveUp).not.toHaveBeenCalled()
  })

  it('힌트를 받는 중에는 질문을 보내지 않는다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.hint).mockImplementation(never)

    await user.click(screen.getByRole('button', { name: /계시 \d 요청/ }))
    await user.type(screen.getByRole('textbox', { name: '질문' }), '건물주가 했나요?{Enter}')

    expect(api.ask).not.toHaveBeenCalled()
  })

  it('질문을 판정하는 중에는 힌트를 요청할 수 없다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.ask).mockImplementation(never as () => Promise<GameView>)

    await user.type(screen.getByRole('textbox', { name: '질문' }), '건물주가 했나요?{Enter}')
    await user.click(screen.getByRole('button', { name: /계시 \d 요청/ }))

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

it('기록이 없으면 응시자의 침묵과 질문 규칙만 짧게 보여 준다', async () => {
  await openGame({})
  expect(screen.getByText(/응시자는 아직 아무 말도 하지 않았다/)).toBeTruthy()
  expect(screen.getByText(/예, 아니오로 답할 수 있는 것만 물어라/)).toBeTruthy()
  expect(screen.queryByText(/건물주/)).toBeNull()
})

describe('추리 결과와 진상 공개', () => {
  it.each([
    ['ONE_SHORT', '거의 다 왔구나. 조금만 더 들여다보렴.'],
    ['TWO_SHORT', '아직 부족하구나.'],
    ['FAR', '아직 멀었단다.'],
  ] as const)('오답 추리(%s)에는 응시자의 한마디만 보여 주고 개수와 보너스는 보여 주지 않는다', async (closeness, line) => {
    await openGame({ guesses: [{ seq: 1, text: 't', correct: false, closeness }] })
    expect(screen.getAllByText(line).length).toBeGreaterThan(0)
    expect(screen.queryByText(/핵심 \d/)).toBeNull()
    expect(screen.queryByText(/보너스/)).toBeNull()
  })

})

describe('사용 횟수와 점수', () => {
  it('사용 막대와 힌트 자리를 보여 준다', async () => {
    await openGame({ used: 13, nextHintAt: 20, hints: [{ no: 1, seq: 101, text: 'h1', image: null }] })
    expect(screen.getByText('사용 13 / 40')).toBeTruthy()
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('13')
    expect(screen.getByRole('button', { name: '계시 2 요청 (사용 20으로)' })).toBeTruthy()
  })

  it('힌트가 다 열리면 힌트 받기 버튼을 숨긴다', async () => {
    vi.mocked(api.game).mockResolvedValue({ ...game, used: 30, nextHintAt: null, hints: [{ no: 1, seq: 101, text: 'a', image: null }, { no: 2, seq: 102, text: 'b', image: null }, { no: 3, seq: 103, text: 'c', image: null }] })
    render(<GamePage gameId="g1" onExit={() => {}} onEnded={() => {}} />)
    await screen.findByText('사용 30 / 40')
    expect(screen.queryByRole('button', { name: /계시 \d 요청/ })).toBeNull()
  })

  it('마지막 기회에는 질문 탭을 막고 안내를 보여 준다', async () => {
    vi.mocked(api.game).mockResolvedValue({ ...game, used: 40, finalChance: true, nextHintAt: null })
    render(<GamePage gameId="g1" onExit={() => {}} onEnded={() => {}} />)
    expect(await screen.findByText('질문을 모두 썼습니다. 마지막 추리 기회가 한 번 남았습니다.')).toBeTruthy()
    expect((screen.getByRole('tab', { name: '질문하기' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText('이 추리는 횟수를 쓰지 않습니다.')).toBeTruthy()
  })

  it('질문 응답으로 받은 게임 상태로 화면을 바꾼다', async () => {
    const user = userEvent.setup()
    await openGame({ used: 9 })
    vi.mocked(api.ask).mockResolvedValue({
      ...game,
      used: 10,
      hints: [{ no: 1, seq: 101, text: '첫 힌트', image: null }],
      nextHintAt: 20,
      questions: [{ no: 1, seq: 1, text: 'q', answer: 'YES' }],
    })
    await user.type(screen.getByRole('textbox', { name: '질문' }), 'q{Enter}')
    expect(await screen.findByText('첫 힌트')).toBeTruthy()
    expect(screen.getByText('사용 10 / 40')).toBeTruthy()
  })


})

describe('기록이 늘어날 때 화면 위치', () => {
  it('새 기록이 오면 수신 기록 칸 안에서만 맨 아래로 스크롤한다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(Element.prototype.scrollTo).mockClear()
    vi.mocked(api.ask).mockResolvedValue({ ...game, used: 1, questions: [{ no: 1, seq: 1, text: 'q', answer: 'YES' }] })
    await user.type(screen.getByRole('textbox', { name: '질문' }), 'q{Enter}')
    await screen.findByText('그렇단다')
    const log = document.querySelector('.log')
    expect(vi.mocked(Element.prototype.scrollTo).mock.contexts).toContain(log)
    expect(window.scrollTo).not.toHaveBeenCalled()
  })
})

describe('응시자 무전 수신 기록', () => {
  it('계시는 수신 기록 줄에 없고, 막대에서 계시창으로 연다', async () => {
    const user = userEvent.setup()
    await openGame({ used: 11, nextHintAt: 20, hints: [{ no: 1, seq: 2, text: '첫 계시', image: null }] })
    expect(document.querySelector('.log')?.textContent).not.toContain('계시')
    expect(screen.queryByRole('dialog')).toBeNull()
    await user.click(screen.getByRole('button', { name: '계시 1' }))
    const dialog = screen.getByRole('dialog', { name: '계시 1' })
    expect(dialog.textContent).toContain('첫 계시')
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    await user.click(screen.getByRole('button', { name: '계시 1' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('다음 계시 눈금으로 힌트를 요청하고, 잠긴 계시는 누를 수 없다', async () => {
    const user = userEvent.setup()
    await openGame({ used: 11, nextHintAt: 20, hints: [{ no: 1, seq: 2, text: 'a', image: null }] })
    vi.mocked(api.hint).mockImplementation(never)
    expect((screen.getByRole('button', { name: '계시 3' }) as HTMLButtonElement).disabled).toBe(true)
    await user.click(screen.getByRole('button', { name: '계시 2 요청 (사용 20으로)' }))
    expect(api.hint).toHaveBeenCalledTimes(1)
  })

  it('계시창은 그림이 있을 때만 그림을 보여 준다', async () => {
    const user = userEvent.setup()
    await openGame({
      used: 21,
      nextHintAt: 30,
      hints: [
        { no: 1, seq: 1, text: 'a', image: '/api/scenarios/s/images/hint-1' },
        { no: 2, seq: 2, text: 'b', image: null },
      ],
    })
    await user.click(screen.getByRole('button', { name: '계시 1' }))
    expect(screen.getByRole('img', { name: '계시 1 이미지' }).getAttribute('src')).toBe('/api/scenarios/s/images/hint-1')
    await user.click(screen.getByRole('button', { name: '계시 2 보기' }))
    expect(screen.getByRole('dialog', { name: '계시 2' })).toBeTruthy()
    expect(screen.queryByRole('img', { name: /계시 \d 이미지/ })).toBeNull()
  })

  it('새로 열린 계시는 계시창이 자동으로 뜨고, 처음부터 있던 계시는 뜨지 않는다', async () => {
    const user = userEvent.setup()
    await openGame({ used: 9, hints: [] })
    expect(screen.queryByRole('dialog')).toBeNull()
    vi.mocked(api.ask).mockResolvedValue({
      ...game,
      used: 10,
      nextHintAt: 20,
      questions: [{ no: 1, seq: 1, text: 'q', answer: 'YES' }],
      hints: [{ no: 1, seq: 2, text: '새 계시', image: null }],
    })
    await user.type(screen.getByRole('textbox', { name: '질문' }), 'q{Enter}')
    expect(await screen.findByRole('dialog', { name: '계시 1' })).toBeTruthy()
  })

  it('클립보드에는 힌트 요청 버튼이 없고, 송신 단말 문구가 보인다', async () => {
    await openGame()
    expect(document.querySelector('.case-device')?.textContent).not.toContain('요청')
    expect(screen.getByText('송신 단말 // CH-07')).toBeTruthy()
    expect(screen.getByRole('textbox', { name: '질문' }).getAttribute('placeholder')).toBe('예나 아니오로 답할 수 있는 질문을 입력하라')
    expect(screen.getByText(/모든 송신은 기록된다/)).toBeTruthy()
  })

  it('처음 불러온 기록은 연출 없이, 새로 온 답만 연출한다', async () => {
    const user = userEvent.setup()
    await openGame({ questions: [{ no: 1, seq: 1, text: 'q1', answer: 'NO' }] })
    expect(document.querySelectorAll('.transmitting')).toHaveLength(0)
    vi.mocked(api.ask).mockResolvedValue({
      ...game,
      used: 2,
      questions: [
        { no: 1, seq: 1, text: 'q1', answer: 'NO' },
        { no: 2, seq: 2, text: 'q2', answer: 'YES' },
      ],
    })
    await user.type(screen.getByRole('textbox', { name: '질문' }), 'q2{Enter}')
    await vi.waitFor(() => expect(document.querySelectorAll('.transmitting')).toHaveLength(1))
  })

  it('수신 기록 머리와 정답 문구', async () => {
    await openGame({ guesses: [{ seq: 1, text: 't', correct: true, closeness: 'CORRECT' }] })
    expect(screen.getByText('수신 기록')).toBeTruthy()
    expect(screen.getByText('응시자와 연결됨')).toBeTruthy()
    expect(screen.getByText('모두 맞혔구나.')).toBeTruthy()
  })

  it('사건 서류에 대표 이미지가 있으면 보여 준다', async () => {
    await openGame({ scenario: { ...game.scenario, cover: '/api/scenarios/s/images/cover' } })
    expect(screen.getByRole('img', { name: '사건 표지' }).getAttribute('src')).toBe('/api/scenarios/s/images/cover')
  })
})

describe('무전기 조작판', () => {
  it('송신 버튼은 기다리는 동안 수신 대기로 바뀌고 눌리지 않는다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.ask).mockImplementation(never as () => Promise<GameView>)
    expect(screen.getByRole('button', { name: '송신 ▶' })).toBeTruthy()
    await user.type(screen.getByRole('textbox', { name: '질문' }), 'q{Enter}')
    const waiting = screen.getByRole('button', { name: '수신 대기' }) as HTMLButtonElement
    expect(waiting.disabled).toBe(true)
  })

  it('조작판 버튼 글자는 질문·추리, 탭 이름은 그대로', async () => {
    await openGame()
    expect(screen.getByRole('tab', { name: '질문하기' }).textContent).toBe('질문')
    expect(screen.getByRole('tab', { name: '추리 제출' }).textContent).toBe('추리')
  })
})

describe('계시창 초점', () => {
  it('Tab 초점이 계시창 밖으로 나가지 않는다', async () => {
    const user = userEvent.setup()
    await openGame({ used: 21, nextHintAt: 30, hints: [{ no: 1, seq: 1, text: 'a', image: null }, { no: 2, seq: 2, text: 'b', image: null }] })
    await user.click(screen.getByRole('button', { name: '계시 1' }))
    const dialog = screen.getByRole('dialog')
    for (let i = 0; i < 5; i++) {
      await user.tab()
      expect(dialog.contains(document.activeElement)).toBe(true)
    }
  })
})

describe('송신 단말 크기', () => {
  it('질문과 추리를 바꿔도 입력칸 줄 수와 안내 줄이 같다', async () => {
    const user = userEvent.setup()
    await openGame()
    const rowsQ = screen.getByRole('textbox', { name: '질문' }).getAttribute('rows')
    const notesQ = document.querySelectorAll('.cost-note').length
    await user.click(screen.getByRole('tab', { name: '추리 제출' }))
    expect(screen.getByRole('textbox', { name: '추리' }).getAttribute('rows')).toBe(rowsQ)
    expect(document.querySelectorAll('.cost-note').length).toBe(notesQ)
  })
})

describe('사용 막대 표시', () => {
  it('막대 위에 계시 자리(10·20·30)와 최종 추리 자리(40) 선을 그린다', async () => {
    await openGame({ used: 13, hints: [{ no: 1, seq: 1, text: 'a', image: null }], nextHintAt: 20 })
    const marks = document.querySelectorAll('.usage-bar .usage-mark')
    expect(marks).toHaveLength(4)
    expect(document.querySelectorAll('.usage-bar .usage-mark.final')).toHaveLength(1)
    expect(screen.getByText('최종 추리')).toBeTruthy()
  })

  it('마지막 기회가 오면 최종 추리 표시가 켜진다', async () => {
    vi.mocked(api.game).mockResolvedValue({ ...game, used: 40, finalChance: true, nextHintAt: null })
    render(<GamePage gameId="g1" onExit={() => {}} onEnded={() => {}} />)
    expect((await screen.findByText('최종 추리')).className).toContain('on')
  })
})

describe('사건 기록 화면', () => {
  it('사건 기록은 종이 클립보드가 아니라 단말 화면에 뜬다', async () => {
    await openGame()
    expect(document.querySelector('.clipboard')).toBeNull()
    const screenEl = document.querySelector('.case-device .case-screen')
    expect(screenEl).not.toBeNull()
    expect(screenEl?.textContent).toContain('사건 기록')
    expect(screenEl?.textContent).toContain('문제')
  })
})

describe('게임이 끝나면 결과 페이지로', () => {
  const solvedGame: GameView = {
    ...game,
    status: 'SOLVED',
    used: 14,
    nextHintAt: null,
    hints: [{ no: 1, seq: 2, text: '계시', image: null }],
    guesses: [{ seq: 1, text: 't', correct: true, closeness: 'CORRECT' }],
    reveal: { truth: 't', keywords: [], bonuses: [], score: 86, bonusPoints: 0 },
  }

  async function openPlaying(onEnded: () => void) {
    vi.mocked(api.game).mockResolvedValue({ ...game, used: 9 })
    const view = render(<GamePage gameId="g1" onExit={() => {}} onEnded={onEnded} />)
    await screen.findByRole('button', { name: '송신 ▶' })
    return view
  }

  /** 가짜 시계에서 추리를 보낸다(응답 처리까지 기다린다). */
  async function sendGuess() {
    fireEvent.click(screen.getByRole('tab', { name: '추리 제출' }))
    const box = screen.getByRole('textbox', { name: '추리' })
    fireEvent.change(box, { target: { value: 't' } })
    await act(async () => {
      fireEvent.submit(box.closest('form')!)
    })
  }

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('정답 추리 응답이 오면 교신 종료 연출 뒤 onEnded', async () => {
    const onEnded = vi.fn()
    await openPlaying(onEnded)
    vi.useFakeTimers()
    vi.mocked(api.guess).mockResolvedValue(solvedGame)
    await sendGuess()
    expect(screen.getByRole('status').textContent).toContain('교신 종료')
    expect(screen.getByRole('status').textContent).toContain('사건 해결')
    // 1.6초로는 넘어가지 않는다(교신 종료와 결과 문구를 읽을 시간)
    act(() => vi.advanceTimersByTime(1600))
    expect(onEnded).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1600))
    expect(onEnded).toHaveBeenCalledTimes(1)
  })

  it('포기해도 같은 연출 뒤 onEnded', async () => {
    const onEnded = vi.fn()
    await openPlaying(onEnded)
    vi.useFakeTimers()
    vi.mocked(api.giveUp).mockResolvedValue({ ...solvedGame, status: 'GAVE_UP', guesses: [], hints: [] })
    fireEvent.click(screen.getByRole('button', { name: '포기하고 진상 보기' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '조사를 중단한다' }))
    })
    expect(screen.getByRole('status').textContent).toContain('교신 종료')
    expect(screen.getByRole('status').textContent).toContain('조사 중단')
    expect(screen.getByRole('status').textContent).toContain('사건은 미해결로 남는다')
    act(() => vi.advanceTimersByTime(3200))
    expect(onEnded).toHaveBeenCalledTimes(1)
  })

  it('마지막 추리가 틀리면 추리 실패 문구', async () => {
    await openPlaying(() => {})
    vi.useFakeTimers()
    vi.mocked(api.guess).mockResolvedValue({ ...solvedGame, status: 'FAILED', guesses: [{ seq: 1, text: 't', correct: false, closeness: 'FAR' }] })
    await sendGuess()
    expect(screen.getByRole('status').textContent).toContain('마지막 추리 실패')
    expect(screen.getByRole('status').textContent).toContain('사건은 미해결로 남는다')
  })

  it('이미 끝난 게임을 열면 연출 없이 바로 onEnded', async () => {
    const onEnded = vi.fn()
    vi.mocked(api.game).mockResolvedValue({ ...solvedGame, status: 'FAILED' })
    render(<GamePage gameId="g1" onExit={() => {}} onEnded={onEnded} />)
    await vi.waitFor(() => expect(onEnded).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('게임이 끝나면서 계시가 새로 열려도 계시창은 뜨지 않는다', async () => {
    await openPlaying(() => {})
    vi.useFakeTimers()
    vi.mocked(api.guess).mockResolvedValue(solvedGame)
    await sendGuess()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('움직임 줄이기면 연출 화면 없이 바로 onEnded', async () => {
    vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('reduce'), media: q }))
    const onEnded = vi.fn()
    await openPlaying(onEnded)
    vi.mocked(api.guess).mockResolvedValue(solvedGame)
    await sendGuess()
    expect(onEnded).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('연출 도중 화면이 사라지면 onEnded를 부르지 않는다', async () => {
    const onEnded = vi.fn()
    const view = await openPlaying(onEnded)
    vi.useFakeTimers()
    vi.mocked(api.guess).mockResolvedValue(solvedGame)
    await sendGuess()
    view.unmount()
    act(() => vi.advanceTimersByTime(3200))
    expect(onEnded).not.toHaveBeenCalled()
  })
})

describe('포기 확인창', () => {
  it('브라우저 확인창 대신 사무소 확인창을 띄우고, 처음 초점은 계속 조사에 둔다', async () => {
    const user = userEvent.setup()
    await openGame()
    await user.click(screen.getByRole('button', { name: '포기하고 진상 보기' }))
    expect(window.confirm).not.toHaveBeenCalled()
    const dialog = screen.getByRole('alertdialog', { name: '조사 중단' })
    expect(dialog.textContent).toContain('진상이 공개되고 교신이 끝납니다')
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '계속 조사한다' }))
    expect(api.giveUp).not.toHaveBeenCalled()
  })

  it('계속 조사하거나 Esc를 누르면 닫히고 포기하지 않는다', async () => {
    const user = userEvent.setup()
    await openGame()
    await user.click(screen.getByRole('button', { name: '포기하고 진상 보기' }))
    await user.click(screen.getByRole('button', { name: '계속 조사한다' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '포기하고 진상 보기' }))
    await user.click(screen.getByRole('button', { name: '포기하고 진상 보기' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(api.giveUp).not.toHaveBeenCalled()
  })

  it('조사를 중단한다를 누르면 포기한다', async () => {
    const user = userEvent.setup()
    await openGame()
    vi.mocked(api.giveUp).mockImplementation(never)
    await user.click(screen.getByRole('button', { name: '포기하고 진상 보기' }))
    await user.click(screen.getByRole('button', { name: '조사를 중단한다' }))
    expect(api.giveUp).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })
})

describe('기록을 다시 불러와야 할 때', () => {
  it('없는 게임은 기록을 찾을 수 없다고 알린다', async () => {
    vi.mocked(api.game).mockRejectedValue(new ApiError('없음', 404))
    render(<GamePage gameId="g1" onExit={() => {}} onEnded={() => {}} />)
    expect(await screen.findByText('기록을 찾을 수 없습니다.')).toBeTruthy()
  })

  it('다른 창에서 바뀌었으면(409) 안내하고 다시 불러오며, 보낸 글은 남긴다', async () => {
    const user = userEvent.setup()
    await openGame()
    const message = '다른 창에서 진행이 바뀌었습니다. 새로고침해 주세요.'
    vi.mocked(api.ask).mockRejectedValue(new ApiError(message, 409))
    vi.mocked(api.game).mockResolvedValue({
      ...game,
      used: 1,
      questions: [{ no: 1, seq: 1, text: '다른 창의 질문', answer: 'YES' }],
    })
    await user.type(screen.getByRole('textbox', { name: '질문' }), '내 질문{Enter}')
    expect(await screen.findByText(message)).toBeTruthy()
    expect(await screen.findByText('다른 창의 질문')).toBeTruthy()
    expect(api.game).toHaveBeenCalledTimes(2)
    expect((screen.getByRole('textbox', { name: '질문' }) as HTMLTextAreaElement).value).toBe('내 질문')
  })

  it('다시 불러온 판이 끝났으면 결과로 넘긴다', async () => {
    const user = userEvent.setup()
    const onEnded = vi.fn()
    vi.mocked(api.game).mockResolvedValueOnce(game)
    render(<GamePage gameId="g1" onExit={() => {}} onEnded={onEnded} />)
    await screen.findByRole('button', { name: '송신 ▶' })
    vi.mocked(api.ask).mockRejectedValue(new ApiError('바뀜', 409))
    vi.mocked(api.game).mockResolvedValueOnce({ ...game, status: 'GAVE_UP' })
    await user.type(screen.getByRole('textbox', { name: '질문' }), 'q{Enter}')
    await vi.waitFor(() => expect(onEnded).toHaveBeenCalledTimes(1))
  })
})

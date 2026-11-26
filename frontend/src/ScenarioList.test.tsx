import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, type MyGame } from './api.ts'
import App from './App.tsx'
import ScenarioList from './ScenarioList.tsx'

vi.mock('./api.ts', async (importActual) => {
  const actual = await importActual<typeof import('./api.ts')>()
  return {
    ...actual,
    api: {
      info: vi.fn().mockResolvedValue({ judge: 'x', fakeJudge: false, judgeOnline: true }),
      scenarios: vi.fn().mockResolvedValue([
        { slug: 'boys-order', title: '열 개의 손목', difficulty: 'hard', problem: '문제 본문', hintCount: 3, cover: null },
        { slug: 'one-week', title: '일주일 연수', difficulty: 'normal', problem: '문제 둘', hintCount: 3, cover: null },
      ]),
      start: vi.fn(),
      myGames: vi.fn().mockResolvedValue([]),
    },
  }
})

afterEach(cleanup)

describe('메인 화면', () => {
  it('머리말과 안내는 응시자를 말하고, 진행자라는 말은 없다', async () => {
    render(<App />)
    await screen.findByText('열 개의 손목')
    expect(document.body.textContent).toContain('응시자')
    expect(document.body.textContent).not.toContain('진행자')
    expect(document.body.textContent).not.toContain('정해진 말')
    expect(document.body.textContent).toContain('바다거북 사무소는 도시에 남은 미해결 사건을 조사합니다.')
    expect(document.body.textContent).toContain('모종의 방법으로 응시자와 교신할 수 있습니다.')
    expect(screen.getByText('응시자는 모든 것을 알고 있다.')).toBeTruthy()
    expect(screen.queryByRole('heading', { name: '미해결 사건' })).toBeNull()
    expect(screen.getByText('바다거북 사무소 // 안내')).toBeTruthy()
  })

  it('사건 목록은 단말 화면에 뜨고, 사건마다 열람 버튼이 있다', async () => {
    render(<ScenarioList onOpen={() => {}} />)
    await screen.findByText('열 개의 손목')
    expect(document.querySelector('.archive-device .archive-screen')).not.toBeNull()
    expect(screen.getAllByRole('button', { name: /열람/ })).toHaveLength(2)
    expect(screen.getByText('미해결 2건', { exact: false })).toBeTruthy()
  })
})

describe('이 기기의 진행 상태', () => {
  const mine = (status: MyGame['status'], extra = {}): MyGame => ({ id: 'g1', scenario: 'boys-order', status, used: 12, limit: 40, score: 0, ...extra })
  const row = (title: string) => screen.getByText(title).closest('li') as HTMLElement

  it('해결한 사건은 점수, 처음인 사건은 표시 없음', async () => {
    vi.mocked(api.myGames).mockResolvedValueOnce([mine('SOLVED', { used: 13, score: 87 })])
    render(<ScenarioList onOpen={() => {}} />)
    expect(await screen.findByText('해결 · 87점')).toBeTruthy()
    expect(within(row('열 개의 손목')).getByText('해결 · 87점')).toBeTruthy()
    expect(row('일주일 연수').querySelector('.archive-progress')).toBeNull()
    expect(screen.getByText('미해결 1건', { exact: false })).toBeTruthy()
  })

  it('진행 중은 사용 횟수, 포기는 해결 못 함', async () => {
    vi.mocked(api.myGames).mockResolvedValueOnce([
      mine('PLAYING'),
      { ...mine('GAVE_UP'), id: 'g2', scenario: 'one-week' },
    ])
    render(<ScenarioList onOpen={() => {}} />)
    expect(await screen.findByText('진행 중 (12/40)')).toBeTruthy()
    expect(within(row('일주일 연수')).getByText('해결 못 함')).toBeTruthy()
    expect(screen.getByText('미해결 2건', { exact: false })).toBeTruthy()
  })

  it('사건을 누르면 시작 응답(이미 있던 판이면 그 판)을 onOpen으로 넘긴다', async () => {
    const game = { id: 'g1', status: 'SOLVED' }
    vi.mocked(api.start).mockResolvedValueOnce(game as never)
    const onOpen = vi.fn()
    render(<ScenarioList onOpen={onOpen} />)
    fireEvent.click(await screen.findByText('열 개의 손목'))
    await vi.waitFor(() => expect(onOpen).toHaveBeenCalledWith(game))
    expect(api.start).toHaveBeenCalledWith('boys-order')
  })

  it('진행 상태를 못 불러와도 사건 목록은 보인다', async () => {
    vi.mocked(api.myGames).mockRejectedValueOnce(new Error('실패'))
    render(<ScenarioList onOpen={() => {}} />)
    expect(await screen.findByText('열 개의 손목')).toBeTruthy()
    expect(screen.getByText('일주일 연수')).toBeTruthy()
    expect(document.querySelector('.error')).toBeNull()
  })
})

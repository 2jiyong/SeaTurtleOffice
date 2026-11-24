import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'
import ScenarioList from './ScenarioList.tsx'

vi.mock('./api.ts', async (importActual) => {
  const actual = await importActual<typeof import('./api.ts')>()
  return {
    ...actual,
    api: {
      info: vi.fn().mockResolvedValue({ judge: 'x', fakeJudge: false, judgeOnline: true }),
      scenarios: vi.fn().mockResolvedValue([
        { slug: 'boys-order', title: '꼬마의 부탁', difficulty: 'hard', problem: '문제 본문', hintCount: 3, cover: null },
        { slug: 'one-week', title: '일주일 연수', difficulty: 'normal', problem: '문제 둘', hintCount: 3, cover: null },
      ]),
      start: vi.fn(),
    },
  }
})

afterEach(cleanup)

describe('메인 화면', () => {
  it('머리말과 안내는 주시자를 말하고, 진행자라는 말은 없다', async () => {
    render(<App />)
    await screen.findByText('꼬마의 부탁')
    expect(document.body.textContent).toContain('주시자')
    expect(document.body.textContent).not.toContain('진행자')
    expect(document.body.textContent).not.toContain('정해진 말')
  })

  it('사건 목록은 단말 화면에 뜨고, 사건마다 열람 버튼이 있다', async () => {
    render(<ScenarioList onStarted={() => {}} />)
    await screen.findByText('꼬마의 부탁')
    expect(document.querySelector('.archive-device .archive-screen')).not.toBeNull()
    expect(screen.getAllByRole('button', { name: /열람/ })).toHaveLength(2)
    expect(screen.getByText('미해결 2건', { exact: false })).toBeTruthy()
  })
})

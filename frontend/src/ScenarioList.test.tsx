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
  it('머리말과 안내는 응시자를 말하고, 진행자라는 말은 없다', async () => {
    render(<App />)
    await screen.findByText('꼬마의 부탁')
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
    render(<ScenarioList onStarted={() => {}} />)
    await screen.findByText('꼬마의 부탁')
    expect(document.querySelector('.archive-device .archive-screen')).not.toBeNull()
    expect(screen.getAllByRole('button', { name: /열람/ })).toHaveLength(2)
    expect(screen.getByText('미해결 2건', { exact: false })).toBeTruthy()
  })
})

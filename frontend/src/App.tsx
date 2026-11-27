import { useEffect, useState } from 'react'
import { trackPage } from './analytics.ts'
import { api, BUDGET_EXHAUSTED, type ServerInfo } from './api.ts'
import GamePage from './GamePage.tsx'
import ResultPage from './ResultPage.tsx'
import { readRoute, replaceRoute } from './route.ts'
import ScenarioList from './ScenarioList.tsx'

export default function App() {
  const [route, setRoute] = useState(() => readRoute(window.location.hash))
  const [info, setInfo] = useState<ServerInfo | null>(null)

  // 오늘 예산이 바닥났는가: 처음 불러온 정보, 또는 하던 중에 받은 예산 429
  const [budgetOut, setBudgetOut] = useState(false)

  useEffect(() => {
    api.info().then(
      (i) => {
        setInfo(i)
        if (i.budgetExhausted) setBudgetOut(true)
      },
      () => setInfo(null),
    )
    const onBudget = () => setBudgetOut(true)
    window.addEventListener(BUDGET_EXHAUSTED, onBudget)
    return () => window.removeEventListener(BUDGET_EXHAUSTED, onBudget)
  }, [])

  useEffect(() => {
    const onHash = () => setRoute(readRoute(window.location.hash))
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  // 화면이 바뀔 때마다 방문 통계에 남긴다(주소는 정규화해서).
  useEffect(() => trackPage(window.location.hash), [route])

  const goHome = () => {
    window.location.hash = '#/'
  }

  const [logoFailed, setLogoFailed] = useState(false)

  return (
    <div className="app">
      <header className="topbar">
        <button className="brand" onClick={goHome}>
          {/* 사이트 상징: frontend/public/brand/logo.webp가 있으면 그 그림, 없으면 기본 육각형 */}
          {logoFailed ? (
            <svg className="brand-mark" viewBox="0 0 32 32" aria-hidden>
              <path d="M16 3.5 27 9.75v12.5L16 28.5 5 22.25V9.75Z" />
              <path className="core" d="M16 10.5 21 13.4v5.2L16 21.5l-5-2.9v-5.2Z" />
            </svg>
          ) : (
            <img className="brand-logo" src="/brand/logo.webp" alt="" onError={() => setLogoFailed(true)} />
          )}
          <span>바다거북 사무소</span>
        </button>
        <p className="brand-sub">응시자는 모든 것을 알고 있습니다. 다만 묻는 말에만, 짧게 답할 뿐입니다.</p>
      </header>
      {budgetOut && <p className="banner">오늘 응시자와의 교신량이 모두 찼습니다. 내일 다시 시도해 주세요.</p>}
      {info && !info.judgeOnline && (
        <p className="banner">
          <b>AI 판정 서버에 연결할 수 없습니다.</b> 질문과 추리를 판정하려면 <code>ai/</code>에서{' '}
          <code>uv run uvicorn app.main:app --port 8000</code>으로 서버를 켜세요.
        </p>
      )}
      {info?.judgeOnline && info.fakeJudge && (
        <p className="banner">
          지금은 <b>가짜 판정기</b>로 실행 중입니다. 모든 질문에 “상관없음”으로 답하고 정답 제출은 늘 오답입니다.
          실제 판정은 <code>ai/.env</code>에 <code>OPENAI_API_KEY</code>를 적고 AI 서버를 다시 켜면 됩니다.
        </p>
      )}
      <main>
        {route.gameId && route.view === 'result' ? (
          <ResultPage
            key={`${route.gameId}/result`}
            gameId={route.gameId}
            onExit={goHome}
            onResume={() => replaceRoute(`#/game/${route.gameId}`)}
            onRestarted={(id) => replaceRoute(`#/game/${id}`)}
          />
        ) : route.gameId ? (
          <GamePage
            key={route.gameId}
            gameId={route.gameId}
            onExit={goHome}
            onEnded={() => replaceRoute(`#/game/${route.gameId}/result`)}
          />
        ) : (
          <ScenarioList
            onOpen={(game) =>
              (window.location.hash = game.status === 'PLAYING' ? `#/game/${game.id}` : `#/game/${game.id}/result`)
            }
          />
        )}
      </main>
      <footer className="fine-print">
        이 열람실에서 한 모든 질문은 기록됩니다. 기록의 소유권은 도시에 있으며, 열람자의 신체와 정신에 생긴 변화는 사무소의 책임이 아닙니다.
      </footer>
    </div>
  )
}

import { useEffect, useState } from 'react'
import { api, type ScenarioSummary } from './api.ts'
import Risk from './Risk.tsx'
import WatcherEye from './WatcherEye.tsx'

export default function ScenarioList({ onStarted }: { onStarted: (gameId: string) => void }) {
  const [scenarios, setScenarios] = useState<ScenarioSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState<string | null>(null)

  useEffect(() => {
    api.scenarios().then(setScenarios, (e: Error) => setError(e.message))
  }, [])

  const start = async (slug: string) => {
    setStarting(slug)
    setError(null)
    try {
      const game = await api.start(slug)
      onStarted(game.id)
    } catch (e) {
      setError((e as Error).message)
      setStarting(null)
    }
  }

  return (
    <section className="list">
      {/* 사무소 공지 */}
      <div className="office-notice">
        <span className="office-notice-head">바다거북 사무소 {'//'} 안내</span>
        <p>
          바다거북 사무소는 도시에 남은 미해결 사건을 조사합니다.
          <br />
          사무소는 모종의 방법으로 <b>응시자</b>와 교신할 수 있습니다. 응시자는 묻는 말에만 짧게 답합니다.
          <br />
          사건을 열람하고, 질문을 송신해 그 전말을 밝혀내십시오.
        </p>
      </div>
      {/* 응시자의 한마디 */}
      <p className="watcher-line">
        <WatcherEye size={30} />
        <span>응시자는 모든 것을 알고 있다.</span>
      </p>
      {error && <p className="error">{error}</p>}
      {!scenarios && !error && <p className="muted">기록을 불러오는 중…</p>}
      {scenarios && (
        <div className="archive">
          <div className="archive-head">
            <h2 className="archive-title">사건 기록 보관소</h2>
            <span className="archive-count">미해결 {scenarios.length}건</span>
          </div>
          <ol className="archive-list">
            {scenarios.map((s, i) => (
              <li key={s.slug}>
                <button className="case-card" onClick={() => start(s.slug)} disabled={starting !== null}>
                  <span className="case-card-frame">
                    {s.cover ? (
                      <img className="case-card-cover" src={s.cover} alt="" />
                    ) : (
                      <span className="case-card-cover case-card-cover-empty" aria-hidden />
                    )}
                    <span className="case-card-title">
                      <span className="case-card-no">사건 {String(i + 1).padStart(3, '0')}</span>
                      <span className="case-card-name">{s.title}</span>
                      <Risk difficulty={s.difficulty} />
                    </span>
                  </span>
                  <span className="case-card-problem">{s.problem}</span>
                  <span className="case-card-cta">{starting === s.slug ? '여는 중…' : '열람'}</span>
                </button>
              </li>
            ))}
            {/* 사건은 계속 들어온다: 다음 기록이 놓일 자리 */}
            <li className="case-card-waiting">
              <WatcherEye size={22} />
              <span>다음 기록 수신 대기 중</span>
            </li>
          </ol>
        </div>
      )}
    </section>
  )
}

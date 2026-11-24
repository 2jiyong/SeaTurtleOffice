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
      <div className="list-head">
        <h1>미해결 사건</h1>
        <p>
          사건 하나를 골라 열람하세요. 질문을 송신하면 주시자가 짧게 답합니다. 그 답들을 이어 붙여 전말을 알아냈다면,
          추리를 송신하세요.
        </p>
      </div>
      {error && <p className="error">{error}</p>}
      {!scenarios && !error && <p className="muted">기록을 불러오는 중…</p>}
      {scenarios && (
        <div className="archive-device">
          <div className="archive-screen">
            <div className="archive-head">
              <WatcherEye size={18} />
              <span className="archive-title">사건 기록 보관소 {'//'} 미해결 {scenarios.length}건</span>
              <span className="signal">
                <i aria-hidden />
                주시자와 연결됨
              </span>
            </div>
            <ol className="archive-list">
              {scenarios.map((s, i) => (
                <li key={s.slug}>
                  <button className="archive-row" onClick={() => start(s.slug)} disabled={starting !== null}>
                    <span className="archive-no">{String(i + 1).padStart(3, '0')}</span>
                    {s.cover && <img className="archive-cover" src={s.cover} alt="" />}
                    <span className="archive-body">
                      <span className="archive-row-head">
                        <span className="archive-name">{s.title}</span>
                        <Risk difficulty={s.difficulty} />
                      </span>
                      <span className="archive-problem">{s.problem}</span>
                    </span>
                    <span className="archive-cta">{starting === s.slug ? '여는 중…' : '열람 ▶'}</span>
                  </button>
                </li>
              ))}
            </ol>
          </div>
        </div>
      )}
    </section>
  )
}

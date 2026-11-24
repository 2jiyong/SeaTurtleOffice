import { useEffect, useState } from 'react'
import { api, type ScenarioSummary } from './api.ts'
import Risk from './Risk.tsx'

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
          사건 기록 하나를 꺼내세요. 질문하면 진행자가 <b>그렇단다</b>, <b>아니란다</b>, <b>중요하지 않단다</b> 같은 정해진 말로 답합니다.
          전말을 알아냈다면 추리를 제출하세요.
        </p>
      </div>
      {error && <p className="error">{error}</p>}
      {!scenarios && !error && <p className="muted">기록을 불러오는 중…</p>}
      <ol className="files">
        {scenarios?.map((s, i) => (
          <li key={s.slug}>
            <button className="file" onClick={() => start(s.slug)} disabled={starting !== null}>
              <span className="file-tab">사건 {String(i + 1).padStart(3, '0')}</span>
              {s.cover && <img className="file-cover" src={s.cover} alt="" />}
              <span className="file-head">
                <span className="file-title">{s.title}</span>
                <Risk difficulty={s.difficulty} />
              </span>
              <span className="file-problem">{s.problem}</span>
              <span className="file-cta">{starting === s.slug ? '기록을 여는 중…' : '이 사건 맡기'}</span>
            </button>
          </li>
        ))}
      </ol>
    </section>
  )
}

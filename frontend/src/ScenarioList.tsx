import { useEffect, useState } from 'react'
import { api, progressLabel, type GameView, type MyGame, type ScenarioSummary } from './api.ts'
import Risk from './Risk.tsx'
import WatcherEye from './WatcherEye.tsx'

/** 진행 상태 표시의 색 갈래 */
const PROGRESS_KIND: Record<MyGame['status'], string> = {
  PLAYING: 'playing',
  SOLVED: 'solved',
  FAILED: 'unsolved',
  GAVE_UP: 'unsolved',
}

/**
 * 사건 목록. 이 기기에서 열어 본 사건에는 진행 상태를 붙인다.
 * 사건을 누르면 그 사건의 판(있으면 이어서, 없으면 새로)을 onOpen으로 넘긴다.
 */
export default function ScenarioList({ onOpen }: { onOpen: (game: GameView) => void }) {
  const [scenarios, setScenarios] = useState<ScenarioSummary[] | null>(null)
  // 이 기기의 판. 불러오지 못하면 상태 표시 없이 목록만 보인다.
  const [mine, setMine] = useState<Map<string, MyGame>>(() => new Map())
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState<string | null>(null)

  useEffect(() => {
    api.scenarios().then(setScenarios, (e: Error) => setError(e.message))
    api.myGames().then(
      (games) => setMine(new Map(games.map((g) => [g.scenario, g]))),
      () => {},
    )
  }, [])

  const start = async (slug: string) => {
    setStarting(slug)
    setError(null)
    try {
      onOpen(await api.start(slug))
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
        <div className="archive-device">
          <div className="archive-screen">
            <div className="archive-head">
              <WatcherEye size={18} />
              <span className="archive-title">사건 기록 보관소 {'//'} 미해결 {scenarios.filter((s) => mine.get(s.slug)?.status !== 'SOLVED').length}건</span>
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
                        {mine.has(s.slug) && (
                          <span className={`archive-progress ${PROGRESS_KIND[mine.get(s.slug)!.status]}`}>
                            {progressLabel(mine.get(s.slug)!)}
                          </span>
                        )}
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

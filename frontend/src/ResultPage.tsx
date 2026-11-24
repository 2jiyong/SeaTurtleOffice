import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError, type GameStatus, type GameView } from './api.ts'
import { CaseScreen, ReceiveLog } from './GameRecords.tsx'
import RevelationDialog from './RevelationDialog.tsx'

type Tab = 'truth' | 'records'

/** 판정 이름. 실패와 포기는 같은 이름에 보조 문구만 다르다. */
const VERDICT: Record<Exclude<GameStatus, 'PLAYING'>, { title: string; sub: string | null }> = {
  SOLVED: { title: '사건 해결', sub: null },
  FAILED: { title: '미해결 종결', sub: '마지막 추리 실패' },
  GAVE_UP: { title: '미해결 종결', sub: '조사 중단' },
}

const NO_FRESH = new Set<number>()

/** 끝난 사건의 결과 페이지. 판정과 점수, [진상] [기록] 탭. */
export default function ResultPage({
  gameId,
  onExit,
  onResume,
}: {
  gameId: string
  onExit: () => void
  onResume: () => void
}) {
  const [game, setGame] = useState<GameView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('truth')
  const [openHint, setOpenHint] = useState<number | null>(null)
  const returnFocus = useRef<HTMLElement | null>(null)
  const closeHint = useCallback(() => {
    setOpenHint(null)
    returnFocus.current?.focus()
  }, [])

  useEffect(() => {
    api.game(gameId).then(
      (g) => (g.status === 'PLAYING' ? onResume() : setGame(g)),
      (e: Error) =>
        setLoadError(e instanceof ApiError && e.status === 404 ? '게임이 만료되었거나 서버가 다시 시작되었습니다.' : e.message),
    )
    // onResume은 주소를 바꾸는 함수라 다시 불러올 이유가 되지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gameId])

  if (loadError) {
    return (
      <section className="game">
        <p className="error">{loadError}</p>
        <button className="btn" onClick={onExit}>
          목록으로
        </button>
      </section>
    )
  }
  if (!game || game.status === 'PLAYING' || !game.reveal) return <p className="muted center">기록을 불러오는 중…</p>

  const reveal = game.reveal
  const solved = game.status === 'SOLVED'
  const verdict = VERDICT[game.status]
  const scoreLine =
    reveal.bonusPoints > 0
      ? `100 − 사용 ${game.used} + 보너스 ${reveal.bonusPoints} = ${reveal.score}`
      : `100 − 사용 ${game.used} = ${reveal.score}`

  return (
    <section className="result">
      <header className={`result-head ${solved ? 'solved' : 'closed'}`}>
        <span className="result-label">교신 종료 {'//'} 사건 기록 봉인</span>
        <div className="result-verdict">
          <h1>{verdict.title}</h1>
          <p className="result-score">{reveal.score}점</p>
        </div>
        <p className="result-sub">{solved ? scoreLine : verdict.sub}</p>
        <div className="result-bar">
          <div className="result-tabs" role="tablist" aria-label="결과 보기">
            <button
              type="button"
              role="tab"
              id="result-tab-truth"
              aria-controls="result-panel"
              aria-selected={tab === 'truth'}
              className={tab === 'truth' ? 'on' : ''}
              onClick={() => setTab('truth')}
            >
              진상
            </button>
            <button
              type="button"
              role="tab"
              id="result-tab-records"
              aria-controls="result-panel"
              aria-selected={tab === 'records'}
              className={tab === 'records' ? 'on' : ''}
              onClick={() => setTab('records')}
            >
              기록
            </button>
          </div>
          <button className="btn result-exit" onClick={onExit}>
            목록으로
          </button>
        </div>
      </header>

      <div id="result-panel" role="tabpanel" aria-labelledby={tab === 'truth' ? 'result-tab-truth' : 'result-tab-records'}>
        {tab === 'truth' ? (
          <article className={`reveal ${solved ? 'solved' : 'closed'}`}>
            <h2>{game.scenario.title}</h2>
            <p className="truth">{reveal.truth}</p>
            <h3>핵심 키워드</h3>
            <ul>
              {reveal.keywords.map((k, i) => (
                <li key={i}>
                  <strong>{k.name}</strong>: {k.description}
                </li>
              ))}
            </ul>
            {reveal.bonuses.length > 0 && (
              <>
                <h3>
                  보너스 키워드{' '}
                  <span className="bonus-count">
                    보너스 {reveal.bonuses.filter((b) => b.found).length}/{reveal.bonuses.length}
                  </span>
                </h3>
                <ul className="bonuses">
                  {reveal.bonuses.map((b, i) => (
                    <li key={i} className={b.found ? 'found' : ''}>
                      <strong>{b.name}</strong>: {b.description}
                      {b.found && <span className="found-mark">맞힘</span>}
                    </li>
                  ))}
                </ul>
              </>
            )}
            <p className="reveal-meta">
              사용 {game.used} / {game.limit}, 질문 {game.questions.length}개, 힌트 {game.hints.length}개.
            </p>
          </article>
        ) : (
          <div className="game">
            <CaseScreen game={game} label="사건 기록 // 봉인" />
            <div className="board">
              <ReceiveLog
                game={game}
                busy={false}
                freshSeqs={NO_FRESH}
                pending={null}
                onOpenHint={(i, el) => {
                  returnFocus.current = el
                  setOpenHint(i)
                }}
              />
            </div>
          </div>
        )}
      </div>
      {openHint !== null && (
        <RevelationDialog hints={game.hints} index={openHint} onIndex={setOpenHint} onClose={closeHint} />
      )}
    </section>
  )
}

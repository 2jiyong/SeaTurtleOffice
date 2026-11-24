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
  // 판정 제목. 들어오면 여기에 초점을 둬서, 화면 읽기 프로그램이 판정부터 읽게 한다.
  const verdictRef = useRef<HTMLHeadingElement>(null)
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

  // 게임 화면에서 아래로 내려간 채 넘어와도 판정과 점수부터 보이게 맨 위로 올린다.
  const loaded = game !== null
  useEffect(() => {
    if (!loaded) return
    window.scrollTo(0, 0)
    verdictRef.current?.focus()
  }, [loaded])

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
          <h1 ref={verdictRef} tabIndex={-1}>
            {verdict.title}
          </h1>
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
          <div className={`truth-panel ${solved ? 'solved' : 'closed'}`}>
            {/* 정답 조건: 무엇을 맞혀야 했는지부터 */}
            <section className="conditions" aria-labelledby="conditions-title">
              <div className="conditions-head">
                <h2 id="conditions-title">정답 조건</h2>
                <span>
                  밝힌 핵심 {reveal.keywords.filter((k) => k.found).length}/{reveal.keywords.length}
                </span>
              </div>
              <ol className="key-cards">
                {reveal.keywords.map((k, i) => (
                  <li key={i} className={`key-card ${k.found ? 'found' : 'missed'}`}>
                    <span className="key-no">핵심 {String(i + 1).padStart(2, '0')}</span>
                    <span className="key-mark">{k.found ? '밝힘' : '놓침'}</span>
                    <strong className="key-name">{k.name}</strong>
                    <p className="key-desc">{k.description}</p>
                  </li>
                ))}
              </ol>
              {reveal.bonuses.length > 0 && (
                <>
                  <div className="conditions-head bonus-head">
                    <h3>보너스</h3>
                    <span className="bonus-count">
                      보너스 {reveal.bonuses.filter((b) => b.found).length}/{reveal.bonuses.length}
                    </span>
                  </div>
                  <ul className="bonus-cards">
                    {reveal.bonuses.map((b, i) => (
                      <li key={i} className={`bonus-card ${b.found ? 'found' : 'missed'}`}>
                        <span className="bonus-mark">{b.found ? '맞힘' : '놓침'}</span>
                        <strong>{b.name}</strong>
                        <p>{b.description}</p>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </section>

            {/* 사건의 전말 */}
            <section className={`reveal ${solved ? 'solved' : 'closed'}`} aria-label="사건의 전말">
              <span className="story-label" aria-hidden>
                사건의 전말
              </span>
              <h2>{game.scenario.title}</h2>
              <p className="truth">{reveal.truth}</p>
              <p className="reveal-meta">
                사용 {game.used} / {game.limit}, 질문 {game.questions.length}개, 힌트 {game.hints.length}개.
              </p>
            </section>
          </div>
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

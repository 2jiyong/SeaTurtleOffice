import { trackEvent } from './analytics.ts'
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError, askedCount, type GameStatus, type GameView } from './api.ts'
import ConfirmDialog from './ConfirmDialog.tsx'
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

/** 끝난 사건의 결과 페이지. 판정과 등급, [진상] [기록] 탭. 초기화하면 기록을 지우고 새 판으로 간다. */
export default function ResultPage({
  gameId,
  onExit,
  onResume,
  onRestarted,
}: {
  gameId: string
  onExit: () => void
  onResume: () => void
  /** 초기화 뒤 새로 시작한 판의 id */
  onRestarted: (gameId: string) => void
}) {
  const [game, setGame] = useState<GameView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('truth')
  const [openHint, setOpenHint] = useState<number | null>(null)
  const returnFocus = useRef<HTMLElement | null>(null)
  const [confirmingReset, setConfirmingReset] = useState(false)
  const [resetting, setResetting] = useState(false)
  const [resetError, setResetError] = useState<string | null>(null)
  const resetButton = useRef<HTMLButtonElement>(null)
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
        setLoadError(e instanceof ApiError && e.status === 404 ? '기록을 찾을 수 없습니다.' : e.message),
    )
    // onResume은 주소를 바꾸는 함수라 다시 불러올 이유가 되지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gameId])

  // 게임 화면에서 아래로 내려간 채 넘어와도 판정과 등급부터 보이게 맨 위로 올린다.
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
  const reset = async () => {
    setConfirmingReset(false)
    setResetting(true)
    setResetError(null)
    try {
      try {
        await api.reset(game.id)
      } catch (e) {
        // 404: 지난번에 지우기는 됐고 새 판 시작만 실패했다. 이미 지워졌으니 시작으로 넘어간다.
        if (!(e instanceof ApiError && e.status === 404)) throw e
      }
      const next = await api.start(game.scenario.slug)
      trackEvent('case_reset', game.scenario.slug)
      onRestarted(next.id)
    } catch (e) {
      setResetError((e as Error).message)
      setResetting(false)
      resetButton.current?.focus()
    }
  }
  const solved = game.status === 'SOLVED'
  const verdict = VERDICT[game.status]
  const foundCount = reveal.keywords.filter((k) => k.found).length
  // 등급을 어떻게 매겼는지 한 줄로: 해결은 보정 사용, 미해결은 밝힌 핵심 수
  const gradeLine = solved
    ? reveal.bonusSaved > 0
      ? `사용 ${game.used} − 보너스 ${reveal.bonusSaved} = ${reveal.adjustedUsed}`
      : `사용 ${game.used}`
    : `${verdict.sub} // 핵심 ${foundCount}/${reveal.keywords.length} 밝힘`

  return (
    <section className="result">
      <header className={`result-head ${solved ? 'solved' : 'closed'}`}>
        <span className="result-label">교신 종료 {'//'} 사건 기록 봉인</span>
        <div className="result-verdict">
          <h1 ref={verdictRef} tabIndex={-1}>
            {verdict.title}
          </h1>
          {reveal.grade && (
            <p className={`result-grade g-${reveal.grade}`} aria-label={`${reveal.grade}등급`}>
              {reveal.grade}
            </p>
          )}
        </div>
        <p className="result-sub">{gradeLine}</p>
        {resetError && <p className="error">{resetError}</p>}
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
          <div className="result-actions">
            <button ref={resetButton} className="btn result-exit result-reset" disabled={resetting} onClick={() => setConfirmingReset(true)}>
              {resetting ? '초기화 중…' : '초기화'}
            </button>
            <button className="btn result-exit" onClick={onExit}>
              목록으로
            </button>
          </div>
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

            {/* 계시: 끝난 사건이라 게임 중에 열지 않은 계시도 모두 볼 수 있다 */}
            {reveal.hints.length > 0 && (
              <section className="conditions hint-review" aria-labelledby="hint-review-title">
                <div className="conditions-head">
                  <h2 id="hint-review-title">계시</h2>
                  <span>
                    연 계시 {reveal.hints.filter((h) => h.opened).length}/{reveal.hints.length}
                  </span>
                </div>
                <ol className="hint-cards">
                  {reveal.hints.map((h, i) => (
                    <li key={h.no}>
                      <button
                        type="button"
                        className={`hint-card ${h.opened ? 'opened' : 'unopened'}`}
                        aria-label={`계시 ${h.no} 보기${h.opened ? '' : ' (열지 않음)'}`}
                        onClick={(e) => {
                          returnFocus.current = e.currentTarget
                          setOpenHint(i)
                        }}
                      >
                        {h.image ? <img src={h.image} alt="" /> : <span className="hint-card-blank" aria-hidden />}
                        <span className="hint-card-no">계시 {String(h.no).padStart(2, '0')}</span>
                        <span className="hint-card-mark">{h.opened ? '열람' : '열지 않음'}</span>
                      </button>
                    </li>
                  ))}
                </ol>
              </section>
            )}

            {/* 사건의 전말 */}
            <section className={`reveal ${solved ? 'solved' : 'closed'}`} aria-label="사건의 전말">
              <span className="story-label" aria-hidden>
                사건의 전말
              </span>
              <h2>{game.scenario.title}</h2>
              <p className="truth">{reveal.truth}</p>
              <p className="reveal-meta">
                사용 {game.used} / {game.limit}, 질문 {askedCount(game.questions)}개, 힌트 {game.hints.length}개.
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
                viewAllHints
              />
            </div>
          </div>
        )}
      </div>
      {openHint !== null && (
        <RevelationDialog hints={reveal.hints} index={openHint} onIndex={setOpenHint} onClose={closeHint} />
      )}
      {confirmingReset && (
        <ConfirmDialog
          title="사건 기록 초기화"
          message="이 사건의 질문, 추리, 계시 기록을 비우고 처음부터 다시 시작합니다."
          confirmLabel="초기화"
          cancelLabel="취소"
          onConfirm={reset}
          onCancel={() => {
            setConfirmingReset(false)
            resetButton.current?.focus()
          }}
        />
      )}
    </section>
  )
}

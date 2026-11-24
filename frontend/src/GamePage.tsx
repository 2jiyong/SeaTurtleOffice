import { useEffect, useState, type FormEvent } from 'react'
import { ANSWER_TEXT, api, ApiError, type GameView, type GuessRecord, type QuestionRecord } from './api.ts'
import Risk from './Risk.tsx'

type Mode = 'question' | 'guess'
type Entry = { kind: 'question'; item: QuestionRecord } | { kind: 'guess'; item: GuessRecord }
type Pending = { mode: Mode; text: string } | null

const LIMIT: Record<Mode, number> = { question: 200, guess: 1000 }
/** 추리 한 번이 쓰는 사용 횟수(백엔드 Game.GUESS_COST와 같다) */
const GUESS_COST = 5

export default function GamePage({ gameId, onExit }: { gameId: string; onExit: () => void }) {
  const [game, setGame] = useState<GameView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>('question')
  const [text, setText] = useState('')
  const [pending, setPending] = useState<Pending>(null)
  // 힌트·포기 요청 중. 질문 판정(pending)과 함께, 진행 중에는 게임 상태를 바꾸는 요청을 더 보내지 않는다.
  const [acting, setActing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.game(gameId).then(setGame, (e: Error) =>
      setLoadError(e instanceof ApiError && e.status === 404 ? '게임이 만료되었거나 서버가 다시 시작되었습니다.' : e.message),
    )
  }, [gameId])

  // 기록이 늘면 페이지 맨 아래로 내린다. 그러면 입력칸이 제자리에 놓이고 새 기록이 바로 위에 보인다.
  // (기록 끝을 화면 아래에 맞추면 화면 아래에 고정된 입력칸이 마지막 기록을 가린다.)
  useEffect(() => {
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' })
  }, [game?.questions.length, game?.guesses.length, pending])

  if (loadError) {
    return (
      <section className="game">
        <p className="error">{loadError}</p>
        <button className="btn" onClick={onExit}>목록으로</button>
      </section>
    )
  }
  if (!game) return <p className="muted center">기록을 불러오는 중…</p>

  const playing = game.status === 'PLAYING'
  const busy = acting || pending !== null
  // 사용 횟수를 다 쓰면 추리만 남는다.
  const inputMode: Mode = game.finalChance ? 'guess' : mode
  const entries: Entry[] = [
    ...game.questions.map((item) => ({ kind: 'question' as const, item })),
    ...game.guesses.map((item) => ({ kind: 'guess' as const, item })),
  ].sort((a, b) => a.item.seq - b.item.seq)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const value = text.trim()
    if (!value || busy) return
    setPending({ mode: inputMode, text: value })
    setError(null)
    setText('')
    try {
      if (inputMode === 'question') {
        // 질문 하나로 힌트가 열릴 수 있어서 게임 전체를 받는다.
        setGame(await api.ask(game.id, value))
      } else {
        setGame(await api.guess(game.id, value))
      }
    } catch (err) {
      setError((err as Error).message)
      setText(value)
    } finally {
      setPending(null)
    }
  }

  const act = async (fn: () => Promise<GameView>) => {
    if (busy) return
    setActing(true)
    setError(null)
    try {
      setGame(await fn())
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setActing(false)
    }
  }

  const giveUp = () => {
    if (window.confirm('포기하면 진상이 공개되고 게임이 끝납니다. 포기할까요?')) {
      act(() => api.giveUp(game.id))
    }
  }

  return (
    <section className="game">
      <aside className="case">
        <div className="case-head">
          <span className="case-label">사건 기록</span>
          <Risk difficulty={game.scenario.difficulty} />
        </div>
        <h1>{game.scenario.title}</h1>
        <p className="problem">{game.scenario.problem}</p>

        {game.hints.length > 0 && (
          <ol className="hints">
            {game.hints.map((h, i) => (
              <li key={i} className="hint">
                <span className="hint-no">추가 정보 {i + 1}</span>
                {h}
              </li>
            ))}
          </ol>
        )}

        {playing && (
          <div className="case-actions">
            {game.nextHintAt !== null && (
              <button className="btn btn-paper" disabled={busy} onClick={() => act(() => api.hint(game.id))}>
                힌트 받기 (사용 {game.nextHintAt}으로)
              </button>
            )}
            <button className="btn btn-quiet" disabled={busy} onClick={giveUp}>
              포기하고 진상 보기
            </button>
          </div>
        )}
      </aside>

      <div className="board">
        <div className="transcript">
          <div className="transcript-head">
            <h2>심문 기록</h2>
            <UsageBar game={game} />
          </div>
          <ol className="log" aria-live="polite">
            {entries.length === 0 && !pending && (
              <li className="empty">
                아직 기록된 질문이 없습니다. 예나 아니오로 답할 수 있는 질문부터 시작하세요.
                <br />
                <span className="example">예: “건물주가 직접 무언가를 했나요?”</span>
              </li>
            )}
            {entries.map((e) =>
              e.kind === 'question' ? (
                <li key={`q${e.item.seq}`} className="entry">
                  <span className="entry-no">Q{e.item.no}</span>
                  <p className="entry-text">{e.item.text}</p>
                  <span className={`stamp s-${ANSWER_TEXT[e.item.answer].tone}`}>{ANSWER_TEXT[e.item.answer].say}</span>
                  <span className="stamp-note">{ANSWER_TEXT[e.item.answer].note}</span>
                </li>
              ) : (
                <li key={`g${e.item.seq}`} className="entry entry-guess">
                  <span className="entry-no">추리</span>
                  <p className="entry-text">{e.item.text}</p>
                  <span className={`stamp ${e.item.correct ? 's-yes' : 's-guess'}`}>
                    {e.item.correct ? '정답' : `핵심 ${e.item.matched}/${e.item.total}`}
                  </span>
                  {!e.item.correct && e.item.missing.length > 0 && (
                    <span className="stamp-note">
                      {e.item.missing.map((m, i) => (
                        <span key={i} className="missing-line">
                          {m}
                        </span>
                      ))}
                    </span>
                  )}
                </li>
              ),
            )}
            {pending && (
              <li className={`entry ${pending.mode === 'guess' ? 'entry-guess' : ''}`}>
                <span className="entry-no">{pending.mode === 'question' ? `Q${game.questions.length + 1}` : '추리'}</span>
                <p className="entry-text">{pending.text}</p>
                <span className="stamp s-pending">판정 중</span>
              </li>
            )}
          </ol>
        </div>

        {error && <p className="error">{error}</p>}

        {playing ? (
          <form className="composer" onSubmit={submit}>
            {game.finalChance && <p className="final-chance">질문을 모두 썼습니다. 마지막 추리 기회가 한 번 남았습니다.</p>}
            <div className="tabs" role="tablist" aria-label="입력 종류">
              <button
                type="button"
                role="tab"
                aria-selected={inputMode === 'question'}
                className={inputMode === 'question' ? 'on' : ''}
                disabled={game.finalChance}
                onClick={() => setMode('question')}
              >
                질문하기
              </button>
              <button type="button" role="tab" aria-selected={inputMode === 'guess'} className={inputMode === 'guess' ? 'on' : ''} onClick={() => setMode('guess')}>
                추리 제출
              </button>
            </div>
            <div className="input-row">
              <textarea
                value={text}
                maxLength={LIMIT[inputMode]}
                rows={inputMode === 'guess' ? 4 : 2}
                aria-label={inputMode === 'question' ? '질문' : '추리'}
                placeholder={inputMode === 'question' ? '예나 아니오로 답할 수 있는 질문을 입력하세요' : '사건의 전말을 설명하세요. 무엇이 일어났고, 누가 왜 그랬는지.'}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    e.currentTarget.form?.requestSubmit()
                  }
                }}
              />
              <button className="btn btn-primary" disabled={!text.trim() || busy}>
                {inputMode === 'question' ? '질문하기' : '제출하기'}
              </button>
            </div>
            <span className="counter">
              {text.length}/{LIMIT[inputMode]}자. Enter로 보내고 Shift+Enter로 줄을 바꿉니다.
              {inputMode === 'guess' && (
                <span className="cost-note">{game.finalChance ? '이 추리는 횟수를 쓰지 않습니다.' : `추리는 사용 ${GUESS_COST}를 씁니다.`}</span>
              )}
            </span>
          </form>
        ) : (
          game.reveal && (
            <article className={`reveal ${game.status === 'SOLVED' ? 'solved' : 'closed'}`}>
              <span className="verdict" aria-hidden>
                {VERDICT[game.status]}
              </span>
              <h2>{game.status === 'SOLVED' ? '사건 해결 보고서' : '사건의 진상'}</h2>
              <p className="score">{game.reveal.score}점</p>
              {game.status === 'SOLVED' && (
                <p className="score-line">
                  {game.reveal.bonusPoints > 0
                    ? `100 − 사용 ${game.used} + 보너스 ${game.reveal.bonusPoints} = ${game.reveal.score}`
                    : `100 − 사용 ${game.used} = ${game.reveal.score}`}
                </p>
              )}
              <p className="truth">{game.reveal.truth}</p>
              <h3>핵심 키워드</h3>
              <ul>
                {game.reveal.keywords.map((k, i) => (
                  <li key={i}>
                    <strong>{k.name}</strong>: {k.description}
                  </li>
                ))}
              </ul>
              {game.reveal.bonuses.length > 0 && (
                <>
                  <h3>
                    보너스 키워드{' '}
                    <span className="bonus-count">
                      보너스 {game.reveal.bonuses.filter((b) => b.found).length}/{game.reveal.bonuses.length}
                    </span>
                  </h3>
                  <ul className="bonuses">
                    {game.reveal.bonuses.map((b, i) => (
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
              <button className="btn btn-primary" onClick={onExit}>
                다른 사건 고르기
              </button>
            </article>
          )
        )}
      </div>
    </section>
  )
}

/** 게임 끝 도장 */
const VERDICT: Record<string, string> = { SOLVED: '해결', FAILED: '실패', GAVE_UP: '미해결 종결' }

/** 사용 횟수 막대. 질문할 때마다 차오르고, 힌트가 열리는 자리에 눈금을 둔다. */
function UsageBar({ game }: { game: GameView }) {
  const pct = (n: number) => `${(Math.min(n, game.limit) / game.limit) * 100}%`
  return (
    <div className="usage">
      <span className="usage-label">
        사용 {game.used} / {game.limit}
      </span>
      <div
        className="usage-bar"
        role="progressbar"
        aria-label="사용 횟수"
        aria-valuemin={0}
        aria-valuemax={game.limit}
        aria-valuenow={game.used}
      >
        <div className="usage-fill" style={{ width: pct(game.used) }} />
        {game.hintSteps.map((step, i) => (
          <span key={step} className={`usage-step ${game.used >= step ? 'open' : ''}`} style={{ left: pct(step) }}>
            <span className="usage-step-label">힌트{i + 1}</span>
          </span>
        ))}
      </div>
    </div>
  )
}

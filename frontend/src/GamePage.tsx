import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ANSWER_TEXT, api, ApiError, type Closeness, type GameView, type GuessRecord, type QuestionRecord } from './api.ts'
import RevelationDialog from './RevelationDialog.tsx'
import Risk from './Risk.tsx'
import Transmission from './Transmission.tsx'
import WatcherEye from './WatcherEye.tsx'

type Mode = 'question' | 'guess'
type Entry = { kind: 'question'; item: QuestionRecord } | { kind: 'guess'; item: GuessRecord }
type Pending = { mode: Mode; text: string } | null

const LIMIT: Record<Mode, number> = { question: 200, guess: 1000 }
/** 추리 한 번이 쓰는 사용 횟수(백엔드 Game.GUESS_COST와 같다) */
const GUESS_COST = 5

/** 추리에 대한 응시자의 한마디. 빠진 핵심 개수(0, 1, 2, 3 이상)로만 나뉜다. */
const CLOSENESS_LINES: Record<Closeness, string> = {
  CORRECT: '모두 맞혔구나.',
  ONE_SHORT: '거의 다 왔구나. 조금만 더 들여다보렴.',
  TWO_SHORT: '아직 부족하구나.',
  FAR: '아직 멀었단다.',
}

export default function GamePage({ gameId, onExit }: { gameId: string; onExit: () => void }) {
  const [game, setGame] = useState<GameView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>('question')
  const [text, setText] = useState('')
  const [pending, setPending] = useState<Pending>(null)
  // 힌트·포기 요청 중. 질문 판정(pending)과 함께, 진행 중에는 게임 상태를 바꾸는 요청을 더 보내지 않는다.
  const [acting, setActing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 응답으로 새로 들어온 기록의 seq. 처음 불러온 기록과 이미 있던 기록은 다시 일그러지지 않는다.
  const [freshSeqs, setFreshSeqs] = useState<Set<number>>(() => new Set())
  // 계시창에 띄운 계시의 순서(0부터). 닫혀 있으면 null.
  const [openHint, setOpenHint] = useState<number | null>(null)
  // 계시창을 닫으면 초점을 돌려줄 곳(누른 눈금)
  const returnFocus = useRef<HTMLElement | null>(null)
  // 수신 기록 칸(스크롤 영역)
  const logRef = useRef<HTMLOListElement>(null)
  const closeHint = useCallback(() => {
    setOpenHint(null)
    // 원래 자리가 화면에서 사라졌으면(게임이 끝나 송신 단말이 없어짐) 마지막으로 열린 계시 눈금으로 돌아간다.
    const el = returnFocus.current
    if (el && el.isConnected) el.focus()
    else [...document.querySelectorAll<HTMLElement>('.usage-step.open')].pop()?.focus()
  }, [])
  // 기본 사건 표지 파일이 없으면 표지 자리를 숨긴다.
  const [coverFailed, setCoverFailed] = useState(false)

  useEffect(() => {
    api.game(gameId).then(setGame, (e: Error) =>
      setLoadError(e instanceof ApiError && e.status === 404 ? '게임이 만료되었거나 서버가 다시 시작되었습니다.' : e.message),
    )
  }, [gameId])

  // 기록이 늘면 수신 기록 칸 안에서만 맨 아래로 내린다(페이지 전체는 움직이지 않는다).
  useEffect(() => {
    const log = logRef.current
    log?.scrollTo({ top: log.scrollHeight, behavior: 'smooth' })
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
  const fresh = (seq: number) => freshSeqs.has(seq)
  // 서버가 돌려준 게임으로 바꾸면서, 이전에 없던 기록만 '새로 들어온 것'으로 표시한다.
  const receive = (next: GameView) => {
    const seqsOf = (g: GameView) => [...g.questions, ...g.guesses, ...g.hints].map((e) => e.seq)
    const before = new Set(seqsOf(game))
    setFreshSeqs(new Set(seqsOf(next).filter((s) => !before.has(s))))
    // 계시가 새로 열리는 순간에는 계시창을 자동으로 띄운다.
    if (next.hints.length > game.hints.length) {
      returnFocus.current = document.activeElement as HTMLElement | null
      setOpenHint(next.hints.length - 1)
    }
    setGame(next)
  }
  const cover = game.scenario.cover ?? (coverFailed ? null : '/covers/default.webp')

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
        receive(await api.ask(game.id, value))
      } else {
        receive(await api.guess(game.id, value))
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
      receive(await fn())
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
      <aside className="case-device">
        <div className="case case-screen">
        <div className="case-head">
          <span className="case-label">사건 기록 // 열람 중</span>
          <Risk difficulty={game.scenario.difficulty} />
        </div>
        {cover && (
          <img
            className="case-cover"
            src={cover}
            alt="사건 표지"
            onError={() => (game.scenario.cover ? undefined : setCoverFailed(true))}
          />
        )}
        <h1>{game.scenario.title}</h1>
        <span className="case-field">문제</span>
        <p className="problem">{game.scenario.problem}</p>

        {playing && (
          <div className="case-actions">
            <button className="btn btn-quiet" disabled={busy} onClick={giveUp}>
              포기하고 진상 보기
            </button>
          </div>
        )}
        </div>
      </aside>

      <div className="board">
        <div className="radio-unit">
        <div className="transcript radio">
          <div className="radio-roll" aria-hidden />
          <div className="transcript-head">
            <WatcherEye />
            <h2>수신 기록</h2>
            <span className="signal">
              <i aria-hidden />
              응시자와 연결됨
            </span>
            <UsageBar
              game={game}
              busy={busy}
              onOpen={(i, el) => {
                returnFocus.current = el
                setOpenHint(i)
              }}
              onRequest={() => act(() => api.hint(game.id))}
            />
          </div>
          <ol ref={logRef} className="log" aria-live="polite" tabIndex={0} aria-label="수신 기록 목록">
            {entries.length === 0 && !pending && (
              <li className="empty">
                <span className="example">{'//'} 수신 기록 없음</span>
                <br />
                응시자는 아직 아무 말도 하지 않았다.
                <br />
                예, 아니오로 답할 수 있는 것만 물어라.
              </li>
            )}
            {entries.map((e) => {
              const isNew = fresh(e.item.seq)
              if (e.kind === 'question') {
                const a = ANSWER_TEXT[e.item.answer]
                return (
                  <li key={`q${e.item.seq}`} className={`entry ${isNew ? 'fresh' : ''}`}>
                    <span className="entry-no">Q{e.item.no}</span>
                    <p className="entry-text">{e.item.text}</p>
                    <Transmission text={a.say} animate={isNew} className={`ans s-${a.tone}`} />
                    <span className="stamp-note">{a.note}</span>
                  </li>
                )
              }
              return (
                <li key={`g${e.item.seq}`} className={`entry entry-guess ${isNew ? 'fresh' : ''}`}>
                  <span className="entry-no">추리</span>
                  <p className="entry-text">{e.item.text}</p>
                  <Transmission
                    text={CLOSENESS_LINES[e.item.closeness]}
                    animate={isNew}
                    className={`ans ${e.item.correct ? 's-yes' : 's-guess'}`}
                  />
                </li>
              )
            })}
            {pending && (
              <li className={`entry ${pending.mode === 'guess' ? 'entry-guess' : ''}`}>
                <span className="entry-no">{pending.mode === 'question' ? `Q${game.questions.length + 1}` : '추리'}</span>
                <p className="entry-text">{pending.text}</p>
                <span className="ans receiving">▓▒░ 수신 중</span>
              </li>
            )}
          </ol>
        </div>

        {playing && (
          <form className="composer" onSubmit={submit}>
            <div className="terminal-head">
              <span>송신 단말 // CH-07</span>
              <span className="terminal-bars" aria-hidden>
                {[8, 16, 24, 32, 40].map((n) => (
                  <i key={n} className={game.used >= n ? 'off' : ''} />
                ))}
              </span>
            </div>
            {game.finalChance && <p className="final-chance">질문을 모두 썼습니다. 마지막 추리 기회가 한 번 남았습니다.</p>}
            <div className="composer-row">
            <div className="tabs" role="tablist" aria-label="입력 종류">
              <button
                type="button"
                role="tab"
                aria-label="질문하기"
                aria-selected={inputMode === 'question'}
                className={inputMode === 'question' ? 'on' : ''}
                disabled={game.finalChance}
                onClick={() => setMode('question')}
              >
                질문
              </button>
              <button
                type="button"
                role="tab"
                aria-label="추리 제출"
                aria-selected={inputMode === 'guess'}
                className={inputMode === 'guess' ? 'on' : ''}
                onClick={() => setMode('guess')}
              >
                추리
              </button>
            </div>
            <div className="input-row">
              <textarea
                value={text}
                maxLength={LIMIT[inputMode]}
                rows={3}
                aria-label={inputMode === 'question' ? '질문' : '추리'}
                placeholder={inputMode === 'question' ? '예나 아니오로 답할 수 있는 질문을 입력하라' : '사건의 전말을 송신하라. 무엇이 일어났고, 누가 왜 그랬는지.'}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    e.currentTarget.form?.requestSubmit()
                  }
                }}
              />
              <button className="send-btn" disabled={!text.trim() || busy}>
                {pending ? '수신 대기' : '송신 ▶'}
              </button>
            </div>
            </div>
            <span className="counter">
              {text.length}/{LIMIT[inputMode]} {'//'} 모든 송신은 기록된다
              {/* 질문·추리 모두 안내 줄을 한 줄 둬서 송신 단말 크기가 바뀌지 않게 한다 */}
              <span className="cost-note">
                {inputMode === 'question'
                  ? '질문은 사용 1을 씁니다.'
                  : game.finalChance
                    ? '이 추리는 횟수를 쓰지 않습니다.'
                    : `추리는 사용 ${GUESS_COST}를 씁니다.`}
              </span>
            </span>
          </form>
        )}
        </div>

        {error && <p className="error">{error}</p>}

        {!playing &&
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
          )}
      </div>
      {openHint !== null && (
        <RevelationDialog hints={game.hints} index={openHint} onIndex={setOpenHint} onClose={closeHint} />
      )}
    </section>
  )
}

/** 게임 끝 도장 */
const VERDICT: Record<string, string> = { SOLVED: '해결', FAILED: '실패', GAVE_UP: '미해결 종결' }

/** 사용 횟수 막대. 질문할 때마다 차오르고, 힌트가 열리는 자리에 눈금을 둔다. */
function UsageBar({
  game,
  busy,
  onOpen,
  onRequest,
}: {
  game: GameView
  busy: boolean
  onOpen: (index: number, el: HTMLElement) => void
  onRequest: () => void
}) {
  const pct = (n: number) => `${(Math.min(n, game.limit) / game.limit) * 100}%`
  const playing = game.status === 'PLAYING'
  return (
    <div className="usage">
      <div className="usage-top">
        <span className="usage-label">
          사용 {game.used} / {game.limit}
        </span>
        {/* 막대 오른쪽 끝(40) 위: 사용을 다 쓰면 최종 추리 한 번 */}
        <span className={`usage-final ${game.finalChance ? 'on' : ''}`}>최종 추리</span>
      </div>
      <div
        className="usage-bar"
        role="progressbar"
        aria-label="사용 횟수"
        aria-valuemin={0}
        aria-valuemax={game.limit}
        aria-valuenow={game.used}
      >
        <div className="usage-fill" style={{ width: pct(game.used) }} />
        {/* 막대 위의 자리 선: 계시(10·20·30)와 최종 추리(40) */}
        {game.hintSteps.map((step) => (
          <span key={step} className={`usage-mark ${game.used >= step ? 'open' : ''}`} style={{ left: pct(step) }} aria-hidden />
        ))}
        <span className={`usage-mark final ${game.finalChance ? 'open' : ''}`} style={{ left: pct(game.limit) }} aria-hidden />
      </div>
      {/* 힌트 눈금: 열린 계시는 계시창, 다음 계시는 요청, 그 뒤는 잠김 */}
      <div className="usage-steps">
        {game.hintSteps.map((step, i) => {
          const no = i + 1
          const open = i < game.hints.length
          const next = !open && playing && game.nextHintAt === step
          const label = open ? `계시 ${no}` : next ? `계시 ${no} 요청 (사용 ${step}으로)` : `계시 ${no}`
          return (
            <button
              key={step}
              type="button"
              className={`usage-step ${open ? 'open' : next ? 'next' : 'locked'}`}
              style={{ left: pct(step) }}
              disabled={(!open && !next) || (next && busy)}
              onClick={(e) => (open ? onOpen(i, e.currentTarget) : onRequest())}
            >
              {label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

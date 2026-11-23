import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ANSWER_LABEL, api, ApiError, type GameView, type GuessRecord, type QuestionRecord } from './api.ts'
import Risk from './Risk.tsx'

type Mode = 'question' | 'guess'
type Entry = { kind: 'question'; item: QuestionRecord } | { kind: 'guess'; item: GuessRecord }
type Pending = { mode: Mode; text: string } | null

const LIMIT: Record<Mode, number> = { question: 200, guess: 1000 }

export default function GamePage({ gameId, onExit }: { gameId: string; onExit: () => void }) {
  const [game, setGame] = useState<GameView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>('question')
  const [text, setText] = useState('')
  const [pending, setPending] = useState<Pending>(null)
  // 힌트·포기 요청 중. 질문 판정(pending)과 함께, 진행 중에는 게임 상태를 바꾸는 요청을 더 보내지 않는다.
  const [acting, setActing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const logEnd = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.game(gameId).then(setGame, (e: Error) =>
      setLoadError(e instanceof ApiError && e.status === 404 ? '게임이 만료되었거나 서버가 다시 시작되었습니다.' : e.message),
    )
  }, [gameId])

  useEffect(() => {
    logEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
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
  const hintsLeft = game.scenario.hintCount - game.hints.length
  const entries: Entry[] = [
    ...game.questions.map((item) => ({ kind: 'question' as const, item })),
    ...game.guesses.map((item) => ({ kind: 'guess' as const, item })),
  ].sort((a, b) => a.item.seq - b.item.seq)

  // 핵심 질문에는 나온 순서대로 증거 번호를 붙인다.
  const evidenceNo = new Map<number, number>()
  game.questions
    .filter((q) => q.key)
    .sort((a, b) => a.seq - b.seq)
    .forEach((q, i) => evidenceNo.set(q.seq, i + 1))

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const value = text.trim()
    if (!value || busy) return
    setPending({ mode, text: value })
    setError(null)
    setText('')
    try {
      if (mode === 'question') {
        const record = await api.ask(game.id, value)
        setGame((g) => (g ? { ...g, questions: [...g.questions, record] } : g))
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
            <button className="btn btn-paper" disabled={hintsLeft <= 0 || busy} onClick={() => act(() => api.hint(game.id))}>
              힌트 받기 <span className="count">{hintsLeft}/{game.scenario.hintCount}</span>
            </button>
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
            <span>
              질문 {game.questions.length} / 확보한 증거 {evidenceNo.size}
            </span>
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
                  <span className={`stamp s-${e.item.answer}`}>{ANSWER_LABEL[e.item.answer]}</span>
                  {e.item.key && <span className="evidence">증거 {evidenceNo.get(e.item.seq)}</span>}
                </li>
              ) : (
                <li key={`g${e.item.seq}`} className="entry entry-guess">
                  <span className="entry-no">추리</span>
                  <p className="entry-text">{e.item.text}</p>
                  <span className={`stamp ${e.item.correct ? 's-YES' : 's-GUESS'}`}>
                    {e.item.correct ? '정답' : `핵심 ${e.item.matched}/${e.item.total}`}
                  </span>
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
          <div ref={logEnd} />
        </div>

        {error && <p className="error">{error}</p>}

        {playing ? (
          <form className="composer" onSubmit={submit}>
            <div className="tabs" role="tablist" aria-label="입력 종류">
              <button type="button" role="tab" aria-selected={mode === 'question'} className={mode === 'question' ? 'on' : ''} onClick={() => setMode('question')}>
                질문하기
              </button>
              <button type="button" role="tab" aria-selected={mode === 'guess'} className={mode === 'guess' ? 'on' : ''} onClick={() => setMode('guess')}>
                추리 제출
              </button>
            </div>
            <div className="input-row">
              <textarea
                value={text}
                maxLength={LIMIT[mode]}
                rows={mode === 'guess' ? 4 : 2}
                aria-label={mode === 'question' ? '질문' : '추리'}
                placeholder={mode === 'question' ? '예나 아니오로 답할 수 있는 질문을 입력하세요' : '사건의 전말을 설명하세요. 무엇이 일어났고, 누가 왜 그랬는지.'}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    e.currentTarget.form?.requestSubmit()
                  }
                }}
              />
              <button className="btn btn-primary" disabled={!text.trim() || busy}>
                {mode === 'question' ? '질문하기' : '제출하기'}
              </button>
            </div>
            <span className="counter">
              {text.length}/{LIMIT[mode]}자. Enter로 보내고 Shift+Enter로 줄을 바꿉니다.
            </span>
          </form>
        ) : (
          game.reveal && (
            <article className={`reveal ${game.status === 'SOLVED' ? 'solved' : 'closed'}`}>
              <span className="verdict" aria-hidden>
                {game.status === 'SOLVED' ? '해결' : '미해결 종결'}
              </span>
              <h2>{game.status === 'SOLVED' ? '사건 해결 보고서' : '사건의 진상'}</h2>
              <p className="truth">{game.reveal.truth}</p>
              <h3>핵심 포인트</h3>
              <ul>
                {game.reveal.keyPoints.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
              <p className="reveal-meta">
                질문 {game.questions.length}개, 힌트 {game.hints.length}개를 써서 {game.status === 'SOLVED' ? '해결했습니다' : '종결했습니다'}.
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

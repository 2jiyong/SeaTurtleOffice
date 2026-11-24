import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, type GameView } from './api.ts'
import ConfirmDialog from './ConfirmDialog.tsx'
import { CaseScreen, ReceiveLog, type Pending } from './GameRecords.tsx'
import RevelationDialog from './RevelationDialog.tsx'
import SignalCut from './SignalCut.tsx'

type Mode = 'question' | 'guess'

const LIMIT: Record<Mode, number> = { question: 200, guess: 1000 }
/** 추리 한 번이 쓰는 사용 횟수(백엔드 Game.GUESS_COST와 같다) */
const GUESS_COST = 5

/**
 * 진행 중인 사건 화면. 게임이 끝나면 신호 단절 연출을 보여 준 뒤 onEnded로 결과 페이지에 넘긴다.
 * 이미 끝난 게임을 열면 연출 없이 바로 onEnded를 부른다.
 */
export default function GamePage({ gameId, onExit, onEnded }: { gameId: string; onExit: () => void; onEnded: () => void }) {
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
  // 이번 화면에서 게임이 끝났다: 신호 단절 연출 중
  const [ending, setEnding] = useState(false)
  // 포기 확인창이 떠 있는가
  const [confirmingGiveUp, setConfirmingGiveUp] = useState(false)
  const giveUpButton = useRef<HTMLButtonElement>(null)
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

  useEffect(() => {
    api.game(gameId).then((g) => (g.status === 'PLAYING' ? setGame(g) : onEnded()), (e: Error) =>
      setLoadError(e instanceof ApiError && e.status === 404 ? '게임이 만료되었거나 서버가 다시 시작되었습니다.' : e.message),
    )
    // onEnded는 주소를 바꾸는 함수라 다시 불러올 이유가 되지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
  // 서버가 돌려준 게임으로 바꾸면서, 이전에 없던 기록만 '새로 들어온 것'으로 표시한다.
  const receive = (next: GameView) => {
    const seqsOf = (g: GameView) => [...g.questions, ...g.guesses, ...g.hints].map((e) => e.seq)
    const before = new Set(seqsOf(game))
    setFreshSeqs(new Set(seqsOf(next).filter((s) => !before.has(s))))
    if (next.status !== 'PLAYING') {
      // 게임이 끝났다. 새로 열린 계시가 있어도 계시창 대신 결과 페이지로 넘어간다.
      setOpenHint(null)
      setEnding(true)
    } else if (next.hints.length > game.hints.length) {
      // 계시가 새로 열리는 순간에는 계시창을 자동으로 띄운다.
      returnFocus.current = document.activeElement as HTMLElement | null
      setOpenHint(next.hints.length - 1)
    }
    setGame(next)
  }

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

  const cancelGiveUp = () => {
    setConfirmingGiveUp(false)
    giveUpButton.current?.focus()
  }
  const confirmGiveUp = () => {
    setConfirmingGiveUp(false)
    act(() => api.giveUp(game.id))
  }

  return (
    <section className="game">
      <CaseScreen game={game} label="사건 기록 // 열람 중">
        {playing && (
          <div className="case-actions">
            <button ref={giveUpButton} className="btn btn-quiet" disabled={busy} onClick={() => setConfirmingGiveUp(true)}>
              포기하고 진상 보기
            </button>
          </div>
        )}
      </CaseScreen>

      <div className="board">
        <ReceiveLog
          game={game}
          busy={busy}
          freshSeqs={freshSeqs}
          pending={pending}
          logRef={logRef}
          onOpenHint={(i, el) => {
            returnFocus.current = el
            setOpenHint(i)
          }}
          onRequestHint={() => act(() => api.hint(game.id))}
        >
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
        </ReceiveLog>

        {error && <p className="error">{error}</p>}

      </div>
      {confirmingGiveUp && (
        <ConfirmDialog
          title="조사 중단"
          message="포기하면 이 사건의 진상이 공개되고 교신이 끝납니다. 점수는 0점입니다."
          cancelLabel="계속 조사한다"
          confirmLabel="조사를 중단한다"
          onCancel={cancelGiveUp}
          onConfirm={confirmGiveUp}
        />
      )}
      {ending && game.status !== 'PLAYING' && <SignalCut status={game.status} onDone={onEnded} />}
      {openHint !== null && (
        <RevelationDialog hints={game.hints} index={openHint} onIndex={setOpenHint} onClose={closeHint} />
      )}
    </section>
  )
}

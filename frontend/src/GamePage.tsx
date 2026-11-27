import { trackEvent } from './analytics.ts'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, type GameView } from './api.ts'
import ConfirmDialog from './ConfirmDialog.tsx'
import GuessDialog from './GuessDialog.tsx'
import { CaseScreen, ReceiveLog, type Pending } from './GameRecords.tsx'
import RevelationDialog from './RevelationDialog.tsx'
import SignalCut from './SignalCut.tsx'

/** 질문 한 번의 최대 글자 수(백엔드 QuestionRequest와 같다). */
const QUESTION_LIMIT = 200
/** 틀린 추리 한 번이 쓰는 사용 횟수(백엔드 Game.GUESS_COST와 같다). 맞힌 추리는 쓰지 않는다. */
const GUESS_COST = 3

/**
 * 진행 중인 사건 화면. 게임이 끝나면 신호 단절 연출을 보여 준 뒤 onEnded로 결과 페이지에 넘긴다.
 * 이미 끝난 게임을 열면 연출 없이 바로 onEnded를 부른다.
 */
export default function GamePage({ gameId, onExit, onEnded }: { gameId: string; onExit: () => void; onEnded: () => void }) {
  const [game, setGame] = useState<GameView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [text, setText] = useState('')
  // 추리 창이 떠 있는가. 입력창은 질문 전용이고, 추리는 이 창에서만 보낸다.
  const [guessOpen, setGuessOpen] = useState(false)
  const [guessError, setGuessError] = useState<string | null>(null)
  // 쓰던 추리. 창을 닫아도 남고, 보내고 나서야 비운다.
  const [guessText, setGuessText] = useState('')
  const questionBox = useRef<HTMLTextAreaElement>(null)
  const guessButton = useRef<HTMLButtonElement>(null)
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
      setLoadError(e instanceof ApiError && e.status === 404 ? '기록을 찾을 수 없습니다.' : e.message),
    )
    // onEnded는 주소를 바꾸는 함수라 다시 불러올 이유가 되지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gameId])

  // 마지막 추리 기회가 오면 추리 창을 바로 연다(닫아도 추리 제출 버튼은 남는다).
  const finalChance = game?.finalChance ?? false
  useEffect(() => {
    if (finalChance) setGuessOpen(true)
  }, [finalChance])

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
  // 서버가 돌려준 게임으로 바꾸면서, 이전에 없던 기록만 '새로 들어온 것'으로 표시한다.
  const receive = (next: GameView) => {
    const seqsOf = (g: GameView) => [...g.questions, ...g.guesses, ...g.hints].map((e) => e.seq)
    const before = new Set(seqsOf(game))
    setFreshSeqs(new Set(seqsOf(next).filter((s) => !before.has(s))))
    if (next.status !== 'PLAYING') {
      // 게임이 끝났다. 새로 열린 계시가 있어도 계시창 대신 결과 페이지로 넘어간다.
      setOpenHint(null)
      setEnding(true)
      trackEvent(next.status === 'SOLVED' ? 'case_solved' : 'case_closed', next.scenario.slug)
    } else if (next.hints.length > game.hints.length) {
      // 계시가 새로 열리는 순간에는 계시창을 자동으로 띄운다.
      returnFocus.current = document.activeElement as HTMLElement | null
      setOpenHint(next.hints.length - 1)
    }
    setGame(next)
  }

  // 409: 다른 창에서 판이 바뀌었거나 이미 끝났다. 안내는 그대로 두고 최신 상태로 다시 불러온다.
  const reloadIfStale = (err: unknown) => {
    if (!(err instanceof ApiError) || err.status !== 409) return
    api.game(game.id).then(
      (g) => (g.status === 'PLAYING' ? setGame(g) : onEnded()),
      () => {},
    )
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const value = text.trim()
    if (!value || busy || game.finalChance) return
    setPending({ mode: 'question', text: value })
    setError(null)
    setText('')
    try {
      // 질문 하나로 힌트가 열릴 수 있어서 게임 전체를 받는다.
      receive(await api.ask(game.id, value))
    } catch (err) {
      setError((err as Error).message)
      setText(value)
      reloadIfStale(err)
    } finally {
      setPending(null)
    }
  }

  // 추리 창을 닫고 초점을 질문 입력창으로(마지막 기회라 막혀 있으면 추리 제출 버튼으로) 돌려준다.
  const closeGuess = () => {
    setGuessOpen(false)
    const box = questionBox.current
    if (box && !box.disabled) box.focus()
    else guessButton.current?.focus()
  }

  // 보내면 창을 바로 닫고, 판정을 기다리는 추리는 질문처럼 수신 기록에 '수신 중'으로 보인다.
  // 실패하면 창을 다시 열어 쓴 글과 오류를 보여 준다(글은 보내고 판정이 끝나야 비운다).
  const submitGuess = async (value: string) => {
    if (busy) return
    setPending({ mode: 'guess', text: value })
    setError(null)
    closeGuess()
    try {
      const next = await api.guess(game.id, value)
      // 이 추리로 계시가 열리면 계시창을 닫은 뒤 질문 입력창으로 돌아오게 한다.
      returnFocus.current = questionBox.current
      receive(next)
      setGuessText('')
    } catch (err) {
      setGuessError((err as Error).message)
      setGuessOpen(true)
      reloadIfStale(err)
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
      reloadIfStale(err)
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
      <CaseScreen
        game={game}
        label="사건 기록 // 열람 중"
        footer={
          playing && (
            <>
              <span className="give-up-note">더 나아갈 수 없다면, 조사를 멈추고 진상을 열람할 수 있다.</span>
              <button ref={giveUpButton} className="give-up" disabled={busy} onClick={() => setConfirmingGiveUp(true)}>
                <span className="give-up-mark" aria-hidden />
                포기하고 진상 보기
              </button>
            </>
          )
        }
      />

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
            <div className="input-row">
              <textarea
                ref={questionBox}
                value={text}
                maxLength={QUESTION_LIMIT}
                rows={3}
                aria-label="질문"
                disabled={game.finalChance}
                placeholder="예나 아니오로 답할 수 있는 질문을 입력하라"
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    e.currentTarget.form?.requestSubmit()
                  }
                }}
              />
              <button className="send-btn" disabled={!text.trim() || busy || game.finalChance}>
                {pending?.mode === 'question' ? '수신 대기' : '송신 ▶'}
              </button>
            </div>
            </div>
            {/* 아래 줄: 왼쪽은 글자 수와 비용 안내, 오른쪽은 추리 창을 여는 버튼(질문 입력줄과 떼어 둔다) */}
            <div className="composer-foot">
              <span className="counter">
                {text.length}/{QUESTION_LIMIT} {'//'} 모든 송신은 기록된다
                <span className="cost-note">
                  {game.finalChance ? '질문을 모두 썼습니다. 추리 제출로 마지막 추리를 보내세요.' : '질문은 사용 1을 씁니다.'}
                </span>
              </span>
              <button
                ref={guessButton}
                type="button"
                className="guess-open"
                aria-label="추리 제출"
                disabled={busy}
                onClick={() => {
                  setGuessError(null)
                  setGuessOpen(true)
                }}
              >
                추리 제출 ▸
              </button>
            </div>
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
      {guessOpen && playing && (
        <GuessDialog
          text={guessText}
          onText={setGuessText}
          costNote={game.finalChance ? '이 추리는 횟수를 쓰지 않습니다.' : `추리가 틀리면 사용 ${GUESS_COST}을 씁니다.`}
          pending={pending?.mode === 'guess'}
          error={guessError}
          onSubmit={submitGuess}
          onClose={closeGuess}
        />
      )}
      {ending && game.status !== 'PLAYING' && <SignalCut status={game.status} onDone={onEnded} />}
      {openHint !== null && (
        <RevelationDialog hints={game.hints} index={openHint} onIndex={setOpenHint} onClose={closeHint} />
      )}
    </section>
  )
}

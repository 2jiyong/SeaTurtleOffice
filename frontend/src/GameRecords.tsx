import { useState, type ReactNode, type RefObject } from 'react'
import { ANSWER_TEXT, type Closeness, type GameView, type GuessRecord, type QuestionRecord } from './api.ts'
import Risk from './Risk.tsx'
import Transmission from './Transmission.tsx'
import WatcherEye from './WatcherEye.tsx'

/** 게임 화면과 결과 화면이 함께 쓰는 사건 기록 화면과 수신 기록 화면. */

type Entry = { kind: 'question'; item: QuestionRecord } | { kind: 'guess'; item: GuessRecord }
export type Pending = { mode: 'question' | 'guess'; text: string } | null

/** 추리에 대한 응시자의 한마디. 빠진 핵심 개수(0, 1, 2, 3 이상)로만 나뉜다. */
const CLOSENESS_LINES: Record<Closeness, string> = {
  CORRECT: '모두 맞혔구나.',
  // 두 문장은 두 줄로 보여 준다(수신 기록의 답 칸이 너무 길어지지 않게).
  ONE_SHORT: '거의 다 왔구나.\n조금만 더 들여다보렴.',
  TWO_SHORT: '아직 부족하구나.',
  FAR: '아직 멀었단다.',
}

/** 사건 파일 카드. footer는 문제 본문과 떨어진 카드 맨 아래 칸(게임 화면의 포기 버튼 자리)에 놓인다. */
export function CaseScreen({ game, label, footer }: { game: GameView; label: string; footer?: ReactNode }) {
  // 기본 사건 표지 파일이 없으면 표지 자리를 숨긴다.
  const [coverFailed, setCoverFailed] = useState(false)
  const cover = game.scenario.cover ?? (coverFailed ? null : '/covers/default.webp')
  return (
    <aside className="case-file">
      <div className="case-file-frame">
        {cover ? (
          <img
            className="case-file-cover"
            src={cover}
            alt="사건 표지"
            onError={() => (game.scenario.cover ? undefined : setCoverFailed(true))}
          />
        ) : (
          <span className="case-file-cover case-file-cover-empty" aria-hidden />
        )}
        <div className="case-file-title">
          <span className="case-file-label">{label}</span>
          <h1>{game.scenario.title}</h1>
          <Risk difficulty={game.scenario.difficulty} />
        </div>
      </div>
      <div className="case-file-body">
        <span className="case-field">문제</span>
        <p className="problem">{game.scenario.problem}</p>
        {/* 정답 조건: 핵심을 몇 개 밝혀야 하는지만 알린다 */}
        <div className="case-counts">
          <span className="case-count">
            <b>핵심 {game.scenario.keywordCount}개</b>
            <small>모두 밝히면 해결</small>
          </span>
        </div>
      </div>
      {footer && <div className="case-file-foot">{footer}</div>}
    </aside>
  )
}

/**
 * 수신 기록 무전기(머리, 사용 막대, 기록 목록). children은 수신 기록 아래(게임 화면의 송신 단말 자리)에 놓인다.
 * onRequestHint가 없으면 다음 계시 요청 눈금도 잠긴 모양으로 그린다(읽기 전용).
 * viewAllHints면 열지 않은 계시 눈금도 눌러서 볼 수 있다(결과 페이지).
 */
export function ReceiveLog({
  game,
  busy,
  freshSeqs,
  pending,
  logRef,
  onOpenHint,
  onRequestHint,
  viewAllHints = false,
  children,
}: {
  game: GameView
  busy: boolean
  freshSeqs: Set<number>
  pending: Pending
  logRef?: RefObject<HTMLOListElement | null>
  onOpenHint: (index: number, el: HTMLElement) => void
  onRequestHint?: () => void
  viewAllHints?: boolean
  children?: ReactNode
}) {
  const entries: Entry[] = [
    ...game.questions.map((item) => ({ kind: 'question' as const, item })),
    ...game.guesses.map((item) => ({ kind: 'guess' as const, item })),
  ].sort((a, b) => a.item.seq - b.item.seq)
  return (
    <div className="radio-unit">
      <div className="transcript radio">
        <div className="radio-roll" aria-hidden />
        <div className="transcript-head">
          <WatcherEye />
          <h2>수신 기록</h2>
          {game.status === 'PLAYING' ? (
            <span className="signal">
              <i aria-hidden />
              응시자와 연결됨
            </span>
          ) : (
            <span className="signal off">
              <i aria-hidden />
              교신 종료
            </span>
          )}
          <UsageBar game={game} busy={busy} onOpen={onOpenHint} onRequest={onRequestHint} viewAll={viewAllHints} />
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
            const isNew = freshSeqs.has(e.item.seq)
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
      {children}
    </div>
  )
}

/** 사용 횟수 막대. 질문할 때마다 차오르고, 힌트가 열리는 자리에 눈금을 둔다. */
function UsageBar({
  game,
  busy,
  onOpen,
  onRequest,
  viewAll,
}: {
  game: GameView
  busy: boolean
  onOpen: (index: number, el: HTMLElement) => void
  onRequest?: () => void
  viewAll: boolean
}) {
  const pct = (n: number) => `${(Math.min(n, game.limit) / game.limit) * 100}%`
  const canRequest = game.status === 'PLAYING' && onRequest !== undefined
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
          const next = !open && canRequest && game.nextHintAt === step
          // 결과 페이지: 열지 않은 계시도 눌러 볼 수 있다(모양은 잠긴 채로 두어 열지 않았음을 남긴다).
          const peek = !open && viewAll
          const label = open ? `계시 ${no}` : next ? `계시 ${no} 요청 (사용 ${step}으로)` : peek ? `계시 ${no} (열지 않음)` : `계시 ${no}`
          return (
            <button
              key={step}
              type="button"
              className={`usage-step ${open ? 'open' : next ? 'next' : peek ? 'locked peek' : 'locked'}`}
              style={{ left: pct(step) }}
              disabled={(!open && !next && !peek) || (next && busy)}
              onClick={(e) => (open || peek ? onOpen(i, e.currentTarget) : onRequest?.())}
            >
              {label}
            </button>
          )
        })}
      </div>
    </div>
  )
}

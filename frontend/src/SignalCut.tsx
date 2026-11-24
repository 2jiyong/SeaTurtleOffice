import { useEffect, useRef, useState } from 'react'
import type { GameStatus } from './api.ts'
import { prefersReducedMotion } from './motion.ts'

/** 잡음(0~0.6초) → 화면이 꺼지듯 닫힘(~1.0초) → "교신 종료"(1.05초~) → 결과 한 줄(1.7초~) → 읽을 시간 */
export const SIGNAL_CUT_MS = 3200

type Ended = Exclude<GameStatus, 'PLAYING'>

/** 교신 종료 아래 결과 한 줄: [무슨 일이 있었나, 사건은 어떻게 되었나] */
const OUTCOME: Record<Ended, [string, string]> = {
  SOLVED: ['사건 해결', '진상이 밝혀졌다'],
  FAILED: ['마지막 추리 실패', '사건은 미해결로 남는다'],
  GAVE_UP: ['조사 중단', '사건은 미해결로 남는다'],
}

/**
 * 신호 단절 연출. 게임이 끝나면 화면 전체를 덮고, 끝나면 onDone을 부른다(결과 페이지로 넘어가기).
 * 움직임 줄이기 설정이면 아무것도 그리지 않고 바로 onDone을 부른다. 사라지면 타이머를 정리한다.
 */
export default function SignalCut({ status, onDone }: { status: Ended; onDone: () => void }) {
  const [reduced] = useState(prefersReducedMotion)
  // 연출 도중 onDone이 바뀌어도 타이머를 다시 걸지 않는다.
  const done = useRef(onDone)
  useEffect(() => {
    done.current = onDone
  }, [onDone])

  useEffect(() => {
    if (reduced) {
      done.current()
      return
    }
    const timer = setTimeout(() => done.current(), SIGNAL_CUT_MS)
    return () => clearTimeout(timer)
  }, [reduced])

  if (reduced) return null
  const [what, fate] = OUTCOME[status]
  return (
    <div className={`signal-cut ${status === 'SOLVED' ? 'solved' : 'closed'}`} role="status">
      <div className="signal-cut-noise" aria-hidden />
      <div className="signal-cut-shutter" aria-hidden />
      <div className="signal-cut-msg">
        <p className="signal-cut-text">교신 종료</p>
        <p className="signal-cut-sub">
          <b>{what}</b> {'//'} {fate}
        </p>
      </div>
    </div>
  )
}

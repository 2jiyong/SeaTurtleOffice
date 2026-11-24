import { useEffect, useRef, useState } from 'react'
import { prefersReducedMotion } from './motion.ts'

/** 잡음 → 화면이 꺼지듯 닫힘 → "교신 종료"까지의 길이 */
export const SIGNAL_CUT_MS = 1600

/**
 * 신호 단절 연출. 게임이 끝나면 화면 전체를 덮고, 끝나면 onDone을 부른다(결과 페이지로 넘어가기).
 * 움직임 줄이기 설정이면 아무것도 그리지 않고 바로 onDone을 부른다. 사라지면 타이머를 정리한다.
 */
export default function SignalCut({ solved, onDone }: { solved: boolean; onDone: () => void }) {
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
  return (
    <div className={`signal-cut ${solved ? 'solved' : 'closed'}`} role="status">
      <div className="signal-cut-noise" aria-hidden />
      <div className="signal-cut-shutter" aria-hidden />
      <p className="signal-cut-text">교신 종료</p>
    </div>
  )
}

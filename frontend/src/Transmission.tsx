import { useEffect, useState } from 'react'
import { prefersReducedMotion } from './motion.ts'

const GLYPHS = '▓▒░█#%&*/|?ㅈㅊㅋㅌㅍㅎ'
/** 깨진 글자에서 맞춰지는 시간 */
const DECODE_MS = 800
const TICK_MS = 50

const scramble = (s: string) => [...s].map((c) => (c === ' ' ? ' ' : GLYPHS[Math.floor(Math.random() * GLYPHS.length)])).join('')


/**
 * 무전으로 들어오는 글자. animate가 참이면 깨진 글자에서 한 글자씩 맞춰진 뒤 빨강·파랑으로 한 번 어긋난다.
 * animate는 처음 그릴 때만 본다(이미 있던 기록은 다시 일그러지지 않는다). 움직임 줄이기 설정이면 바로 보여 준다.
 */
export default function Transmission({ text, animate, className = '' }: { text: string; animate: boolean; className?: string }) {
  const [phase, setPhase] = useState<'decoding' | 'glitch' | 'done'>(() => (animate && !prefersReducedMotion() ? 'decoding' : 'done'))
  const [shown, setShown] = useState(() => (phase === 'decoding' ? scramble(text) : text))

  useEffect(() => {
    if (phase !== 'decoding') return
    const start = Date.now()
    const timer = setInterval(() => {
      const p = Math.min(1, (Date.now() - start) / DECODE_MS)
      const n = Math.floor(p * text.length)
      setShown(text.slice(0, n) + scramble(text.slice(n)))
      if (p >= 1) {
        clearInterval(timer)
        setShown(text)
        setPhase('glitch')
      }
    }, TICK_MS)
    return () => clearInterval(timer)
  }, [phase, text])

  useEffect(() => {
    if (phase !== 'glitch') return
    const timer = setTimeout(() => setPhase('done'), 1100)
    return () => clearTimeout(timer)
  }, [phase])

  const state = phase === 'decoding' ? 'transmitting' : phase === 'glitch' ? 'transmitting glitch' : ''
  if (phase === 'done') {
    return <span className={`tx ${className}`}>{text}</span>
  }
  // 연출 중: 깨진 글자는 화면 낭독기에서 숨기고, 완성된 문장을 따로 읽게 한다.
  return (
    <span className={`tx ${className} ${state}`} data-t={text}>
      <span aria-hidden="true">{shown}</span>
      <span className="sr-only">{text}</span>
    </span>
  )
}

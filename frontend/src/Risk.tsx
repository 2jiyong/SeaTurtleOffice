import { DIFFICULTY_LABEL } from './api.ts'

const LEVEL: Record<string, number> = { easy: 1, normal: 2, hard: 3 }

/** 난이도를 위험 등급 눈금(3칸)으로 보여 준다. */
export default function Risk({ difficulty }: { difficulty: string }) {
  const level = LEVEL[difficulty] ?? 0
  const label = DIFFICULTY_LABEL[difficulty] ?? difficulty
  return (
    <span className={`risk r-${level}`} title={`난이도: ${label}`}>
      <span className="risk-bars" aria-hidden>
        {[1, 2, 3].map((n) => (
          <i key={n} className={n <= level ? 'on' : ''} />
        ))}
      </span>
      <span className="risk-label">위험 등급 {label}</span>
    </span>
  )
}

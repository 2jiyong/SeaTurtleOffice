import { useEffect, useRef } from 'react'
import type { HintRecord } from './api.ts'

/**
 * 계시창. 열린 계시 하나를 화면 가운데 띄운다(그림이 있으면 위에, 문장은 아래).
 * 열린 계시끼리 넘겨 볼 수 있고, 닫기·Esc·바깥 누르기로 닫힌다. 열리면 닫기 버튼에 초점을 둔다.
 */
export default function RevelationDialog({
  hints,
  index,
  onIndex,
  onClose,
}: {
  hints: HintRecord[]
  index: number
  onIndex: (i: number) => void
  onClose: () => void
}) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const hint = hints[index]

  useEffect(() => {
    closeRef.current?.focus()
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  if (!hint) return null
  const title = `계시 ${hint.no}`
  return (
    <div className="revelation-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="revelation" role="dialog" aria-modal="true" aria-labelledby="revelation-title">
        <div className="revelation-head">
          <h2 id="revelation-title">{title}</h2>
        </div>
        {hint.image && <img className="revelation-image" src={hint.image} alt={`${title} 이미지`} />}
        <p className="revelation-text">{hint.text}</p>
        <div className="revelation-foot">
          <div className="revelation-nav">
            {hints.map((h, i) => (
              <button
                key={h.no}
                type="button"
                className={i === index ? 'on' : ''}
                aria-label={`계시 ${h.no} 보기`}
                aria-current={i === index}
                onClick={() => onIndex(i)}
              >
                계시 {h.no}
              </button>
            ))}
          </div>
          <button ref={closeRef} type="button" className="revelation-close" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}

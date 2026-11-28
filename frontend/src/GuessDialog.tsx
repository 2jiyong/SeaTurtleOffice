import { useEffect, useRef, type FormEvent } from 'react'

/** 추리 한 번의 최대 글자 수(백엔드 GuessRequest와 같다). */
export const GUESS_LIMIT = 1000

/**
 * 추리 제출 창. 질문 입력창과 떼어 두어, 질문하다가 실수로 추리를 보내지 않게 한다.
 * 쓰던 글은 부모(GamePage)가 들고 있어서 창을 닫았다 열어도 남는다. 보내고 나서야 비운다.
 * 송신이 끝나면 부모가 창을 닫는다. 실패하면 창과 쓴 글을 그대로 두고 오류를 알린다.
 * 송신 중에는 닫거나 다시 보낼 수 없다. Esc로 닫는다(한글 조합 중 제외). 바깥을 누르면 빈 창일 때만 닫는다.
 */
export default function GuessDialog({
  text,
  onText,
  costNote,
  pending,
  error,
  onSubmit,
  onClose,
}: {
  text: string
  onText: (text: string) => void
  costNote: string
  pending: boolean
  error: string | null
  onSubmit: (text: string) => void
  onClose: () => void
}) {
  const boxRef = useRef<HTMLTextAreaElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    boxRef.current?.focus()
  }, [])

  // 송신이 실패하면 초점을 추리 칸으로 돌려준다(누른 버튼은 송신 중에 막혀 초점을 잃는다).
  useEffect(() => {
    if (!pending && error) boxRef.current?.focus()
  }, [pending, error])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !pending && !e.isComposing) onClose()
      if (e.key === 'Tab' && dialogRef.current) {
        const items = [...dialogRef.current.querySelectorAll<HTMLElement>('textarea, button:not(:disabled)')]
        const first = items[0]
        const last = items[items.length - 1]
        const inside = dialogRef.current.contains(document.activeElement)
        if (e.shiftKey && (document.activeElement === first || !inside)) {
          e.preventDefault()
          last.focus()
        } else if (!e.shiftKey && (document.activeElement === last || !inside)) {
          e.preventDefault()
          first.focus()
        }
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose, pending])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const value = text.trim()
    if (!value || pending) return
    onSubmit(value)
  }

  return (
    <div
      className="confirm-backdrop"
      onMouseDown={(e) => e.target === e.currentTarget && !pending && !text.trim() && onClose()}
    >
      <div
        ref={dialogRef}
        className="confirm guess-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="guess-title"
        aria-describedby="guess-cost"
      >
        <span className="confirm-label" aria-hidden>
          송신 단말 {'//'} 추리
        </span>
        <h2 id="guess-title">추리 제출</h2>
        <p className="guess-guide">
          아는 것을 최대한 상세하게 적어라. 무엇이 일어났는지, 누가 왜 그랬는지, 어떻게 그렇게 되었는지까지 빠짐없이 쓸수록 정확하게 판정된다.
        </p>
        <form onSubmit={submit}>
          <textarea
            ref={boxRef}
            value={text}
            maxLength={GUESS_LIMIT}
            rows={8}
            aria-label="추리"
            placeholder="사건의 전말을 송신하라. 무엇이 일어났고, 누가 왜 그랬는지."
            readOnly={pending}
            onChange={(e) => onText(e.target.value)}
          />
          <p className="guess-note">
            <span>
              {text.length}/{GUESS_LIMIT}
            </span>
            <span id="guess-cost">{costNote}</span>
          </p>
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
          <div className="confirm-actions">
            <button type="button" className="confirm-cancel" disabled={pending} onClick={onClose}>
              취소
            </button>
            <button className="confirm-ok" disabled={!text.trim() || pending}>
              {pending ? '수신 대기' : '추리 송신'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

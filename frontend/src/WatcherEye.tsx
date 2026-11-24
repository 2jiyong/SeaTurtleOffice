/** 주시자의 표식(눈). 질문하는 곳(수신 기록 머리)에만 쓴다. 사이트 상징과는 따로다. */
export default function WatcherEye({ size = 22 }: { size?: number }) {
  return (
    <svg className="watcher-eye" width={size} height={size} viewBox="0 0 32 32" aria-hidden>
      <path d="M2 16C7 8 25 8 30 16C25 24 7 24 2 16Z" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="16" cy="16" r="5" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="16" cy="16" r="2.2" fill="#b3261e" />
    </svg>
  )
}

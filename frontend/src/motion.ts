/** 브라우저가 움직임 줄이기 설정인가. 연출은 이 값이 참이면 건너뛴다. */
export function prefersReducedMotion() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

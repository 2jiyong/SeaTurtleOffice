/**
 * 방문 통계(Umami). 배포에서 VITE_UMAMI_SRC와 VITE_UMAMI_WEBSITE_ID를 줄 때만 켜진다.
 * 주소는 목록·게임·결과 셋으로만 묶어 보내고, 판 ID나 질문 내용은 보내지 않는다.
 * 스크립트가 없거나(개발·차단) 아직 안 떴으면 아무것도 하지 않는다.
 */

type Props = Record<string, unknown>

declare global {
  interface Window {
    umami?: { track: (nameOrProps: string | ((props: Props) => Props), data?: Props) => void }
  }
}

export type CaseEvent = 'case_open' | 'case_solved' | 'case_closed' | 'case_reset'

export function pageFor(hash: string): '/' | '/game' | '/result' {
  if (/^#\/game\/[^/]+\/result$/.test(hash)) return '/result'
  if (/^#\/game\/[^/]+$/.test(hash)) return '/game'
  return '/'
}

export function trackPage(hash: string) {
  const url = pageFor(hash)
  window.umami?.track((props) => ({ ...props, url }))
}

export function trackEvent(name: CaseEvent, scenario: string) {
  window.umami?.track(name, { scenario })
}

export function installUmami(src?: string, websiteId?: string) {
  if (!src || !websiteId) return
  const script = document.createElement('script')
  script.defer = true
  script.src = src
  script.dataset.websiteId = websiteId
  script.dataset.autoTrack = 'false'
  script.dataset.excludeHash = 'true'
  // 스크립트가 첫 화면보다 늦게 뜨면 첫 페이지 기록이 빠지므로 뜨는 순간 한 번 남긴다.
  script.onload = () => trackPage(window.location.hash)
  document.head.appendChild(script)
}

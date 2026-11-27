interface ImportMetaEnv {
  /** Umami 스크립트 주소(배포에서만). 예: https://stats.seaturtleoffice.site/script.js */
  readonly VITE_UMAMI_SRC?: string
  /** Umami에 등록한 웹사이트 ID(배포에서만) */
  readonly VITE_UMAMI_WEBSITE_ID?: string
}

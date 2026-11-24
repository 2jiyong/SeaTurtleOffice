/**
 * 주소: #/ (목록), #/game/<id> (게임), #/game/<id>/result (결과).
 * 새로고침해도 서버에 남아 있는 게임으로 돌아온다.
 */
export type Route = { gameId: string | null; view: 'play' | 'result' }

export function readRoute(hash: string): Route {
  const match = hash.match(/^#\/game\/([\w-]+)(\/result)?$/)
  if (!match) return { gameId: null, view: 'play' }
  return { gameId: match[1], view: match[2] ? 'result' : 'play' }
}

/** 뒤로 가기 기록을 남기지 않고 주소를 바꾼다(끝난 게임 화면으로 되돌아가지 않게). */
export function replaceRoute(hash: string) {
  window.location.replace(hash)
}

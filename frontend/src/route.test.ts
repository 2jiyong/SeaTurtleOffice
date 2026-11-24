import { describe, expect, it } from 'vitest'
import { readRoute } from './route.ts'

describe('readRoute', () => {
  it('목록, 게임, 결과 주소를 읽는다', () => {
    expect(readRoute('#/')).toEqual({ gameId: null, view: 'play' })
    expect(readRoute('')).toEqual({ gameId: null, view: 'play' })
    expect(readRoute('#/game/abc')).toEqual({ gameId: 'abc', view: 'play' })
    expect(readRoute('#/game/abc/result')).toEqual({ gameId: 'abc', view: 'result' })
  })
})

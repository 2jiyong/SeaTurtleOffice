export type Answer = 'YES' | 'NO' | 'PARTIAL' | 'IRRELEVANT' | 'INVALID'
export type GameStatus = 'PLAYING' | 'SOLVED' | 'GAVE_UP'

export interface ScenarioSummary {
  slug: string
  title: string
  difficulty: 'easy' | 'normal' | 'hard' | string
  problem: string
  hintCount: number
}

export interface QuestionRecord {
  no: number
  seq: number
  text: string
  answer: Answer
  key: boolean
}

export interface GuessRecord {
  seq: number
  text: string
  matched: number
  total: number
  correct: boolean
}

export interface GameView {
  id: string
  scenario: ScenarioSummary
  status: GameStatus
  questions: QuestionRecord[]
  hints: string[]
  guesses: GuessRecord[]
  reveal: { truth: string; keyPoints: string[] } | null
}

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let message = `요청이 실패했습니다 (${res.status})`
    try {
      const problem = await res.json()
      if (problem?.detail) message = problem.detail
    } catch {
      /* 본문이 JSON이 아니면 기본 메시지를 쓴다 */
    }
    throw new ApiError(message, res.status)
  }
  return res.json() as Promise<T>
}

export interface ServerInfo {
  judge: string
  fakeJudge: boolean
}

export const api = {
  info: () => request<ServerInfo>('/info'),
  scenarios: () => request<ScenarioSummary[]>('/scenarios'),
  start: (scenario: string) => request<GameView>('/games', { scenario }),
  game: (id: string) => request<GameView>(`/games/${id}`),
  ask: (id: string, text: string) => request<QuestionRecord>(`/games/${id}/questions`, { text }),
  guess: (id: string, text: string) => request<GameView>(`/games/${id}/guesses`, { text }),
  hint: (id: string) => request<GameView>(`/games/${id}/hints`, {}),
  giveUp: (id: string) => request<GameView>(`/games/${id}/give-up`, {}),
}

export const ANSWER_LABEL: Record<Answer, string> = {
  YES: '예',
  NO: '아니오',
  PARTIAL: '부분적으로 예',
  IRRELEVANT: '상관없음',
  INVALID: '예/아니오로 답할 수 없는 질문',
}

export const DIFFICULTY_LABEL: Record<string, string> = {
  easy: '쉬움',
  normal: '보통',
  hard: '어려움',
}

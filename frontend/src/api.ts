export type Answer =
  | 'YES'
  | 'NO'
  | 'PARTIAL_SOME'
  | 'PARTIAL_CONDITION'
  | 'IRRELEVANT_UNRELATED'
  | 'IRRELEVANT_UNDECIDED'
  | 'INVALID_FORM'
  | 'INVALID_TRUTH'
  | 'INVALID_RULE'

/** 도장 색. 진행자의 말이 같으면 같은 색이다. */
export type Tone = 'yes' | 'no' | 'partial' | 'irrelevant' | 'invalid'
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
}

export interface GuessRecord {
  seq: number
  text: string
  matched: number
  total: number
  correct: boolean
  /** 빠진 핵심 키워드마다 진행자가 하는 말 */
  missing: string[]
}

export interface Reveal {
  truth: string
  keywords: { name: string; description: string }[]
  /** found: 이번 게임에서 낸 추리 중 하나라도 이 보너스를 맞혔는가 */
  bonuses: { name: string; description: string; found: boolean }[]
}

export interface GameView {
  id: string
  scenario: ScenarioSummary
  status: GameStatus
  questions: QuestionRecord[]
  hints: string[]
  guesses: GuessRecord[]
  reveal: Reveal | null
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
  judgeOnline: boolean
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

/** 진행자의 말(say)과 그 아래 안내 문장(note). 서버는 코드만 보내고, 문장은 여기에만 둔다. */
export const ANSWER_TEXT: Record<Answer, { say: string; note: string; tone: Tone }> = {
  YES: { say: '그렇단다', note: '진상에 비추어 맞는 내용입니다.', tone: 'yes' },
  NO: { say: '아니란다', note: '진상에 비추어 틀린 내용입니다.', tone: 'no' },
  PARTIAL_SOME: { say: '어느정도는 그렇단다', note: '일부만 맞는 내용입니다.', tone: 'partial' },
  PARTIAL_CONDITION: { say: '어느정도는 그렇단다', note: '조건을 붙이면 맞는 내용입니다.', tone: 'partial' },
  IRRELEVANT_UNRELATED: { say: '중요하지 않단다', note: '이 사건과 관련이 없는 내용입니다.', tone: 'irrelevant' },
  IRRELEVANT_UNDECIDED: { say: '중요하지 않단다', note: '이 사건에서 정하지 않은 세부사항입니다.', tone: 'irrelevant' },
  INVALID_FORM: { say: '말해줄 수 없단다', note: '예·아니오로 답할 수 없는 질문입니다.', tone: 'invalid' },
  INVALID_TRUTH: { say: '말해줄 수 없단다', note: '진상을 직접 묻는 질문입니다.', tone: 'invalid' },
  INVALID_RULE: { say: '말해줄 수 없단다', note: '규칙을 벗어난 요청입니다.', tone: 'invalid' },
}

export const DIFFICULTY_LABEL: Record<string, string> = {
  easy: '쉬움',
  normal: '보통',
  hard: '어려움',
}

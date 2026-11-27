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
/** FAILED: 마지막 추리까지 틀렸다 */
export type GameStatus = 'PLAYING' | 'SOLVED' | 'GAVE_UP' | 'FAILED'

export interface ScenarioSummary {
  slug: string
  title: string
  difficulty: 'easy' | 'normal' | 'hard' | string
  problem: string
  hintCount: number
  /** 대표 이미지 주소. 그림이 없으면 null */
  cover: string | null
  /** 밝혀야 할 핵심 키워드 개수(내용은 게임이 끝나야 공개) */
  keywordCount: number
  /** 보너스 키워드 개수 */
  bonusCount: number
}

export interface QuestionRecord {
  no: number
  seq: number
  text: string
  answer: Answer
}

export interface HintRecord {
  no: number
  /** 열린 순간의 순번 */
  seq: number
  text: string
  /** 힌트 그림 주소. 없으면 null */
  image: string | null
}

/** 추리가 진상에 얼마나 가까운지. 맞힌 개수와 빠진 항목은 알려 주지 않는다. */
export type Closeness = 'CORRECT' | 'ONE_SHORT' | 'TWO_SHORT' | 'FAR'

export interface GuessRecord {
  seq: number
  text: string
  correct: boolean
  closeness: Closeness
}

export interface Reveal {
  truth: string
  /** found: 이번 게임에서 낸 추리 중 하나라도 이 핵심을 맞혔는가 */
  keywords: { name: string; description: string; found: boolean }[]
  /** found: 이번 게임에서 낸 추리 중 하나라도 이 보너스를 맞혔는가 */
  bonuses: { name: string; description: string; found: boolean }[]
  /** 끝난 판의 등급(백엔드 Grade). 진행 중이면 null */
  grade: Grade | null
  /** 맞힌 보너스가 덜어 준 사용 횟수(해결한 판만, 보너스 하나에 5) */
  bonusSaved: number
  /** 등급을 매긴 사용 횟수: 해결하면 사용 − bonusSaved(0 아래로는 안 감), 아니면 사용 그대로 */
  adjustedUsed: number
  /** 시나리오의 모든 계시. 끝난 판이라 게임 중에 열지 않은 것(opened: false)도 담는다 */
  hints: RevealHint[]
}

export interface RevealHint {
  no: number
  text: string
  /** 힌트 그림 주소. 없으면 null */
  image: string | null
  /** 게임 중에 열었는가 */
  opened: boolean
}

/**
 * 등급. 해결한 판은 보정 사용 10 이하 S, 20 이하 A, 30 이하 B, 그 위 C.
 * 못 푼 판은 핵심을 하나라도 밝혔으면 D, 아니면 F.
 */
export type Grade = 'S' | 'A' | 'B' | 'C' | 'D' | 'F'

export interface GameView {
  id: string
  scenario: ScenarioSummary
  status: GameStatus
  questions: QuestionRecord[]
  /** 열린 힌트(계시). seq 순으로 질문 사이에 끼워 보여 준다 */
  hints: HintRecord[]
  guesses: GuessRecord[]
  /** 사용 횟수(질문 1, 추리 5). 한도는 limit */
  used: number
  limit: number
  /** 힌트가 열리는 사용 횟수, 예: [10, 20, 30] */
  hintSteps: number[]
  /** 사용 횟수를 다 써서 마지막 추리 한 번만 남았다 */
  finalChance: boolean
  /** 힌트 받기를 누르면 올라갈 사용 횟수. 남은 힌트가 없으면 null */
  nextHintAt: number | null
  reveal: Reveal | null
}

/** 이 기기의 판 하나(메인 페이지 진행 상태). grade는 끝난 판에만 있다. */
export interface MyGame {
  id: string
  scenario: string
  status: GameStatus
  used: number
  limit: number
  grade: Grade | null
}

/** 메인 페이지 사건 줄에 붙는 진행 상태 문구 */
export function progressLabel(g: MyGame): string {
  if (g.status === 'PLAYING') return `진행 중 (${g.used}/${g.limit})`
  const grade = g.grade ? ` · ${g.grade}등급` : ''
  if (g.status === 'SOLVED') return `해결${grade}`
  return `해결 못 함${grade}`
}

export class ApiError extends Error {
  readonly status: number
  /** 429일 때 어떤 한도인지: budget(오늘 전체 예산) / device / ip */
  readonly reason?: string

  constructor(message: string, status: number, reason?: string) {
    super(message)
    this.status = status
    this.reason = reason
  }
}

/** 오늘 전체 예산이 바닥났다는 알림. App이 듣고 상단 안내를 켠다. */
export const BUDGET_EXHAUSTED = 'budget-exhausted'

/**
 * 요청 하나를 기다리는 최대 시간. 백엔드(28초)보다 조금 길게 잡아서, 보통은 백엔드가 먼저 오류를 돌려준다.
 * 백엔드가 아예 답하지 않을 때만 여기서 끊는다.
 */
export const REQUEST_TIMEOUT_MS = 30_000

async function request<T>(path: string, body?: unknown, method?: 'DELETE'): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  let res: Response
  try {
    res = await fetch(`/api${path}`, {
      method: method ?? (body === undefined ? 'GET' : 'POST'),
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
  } catch (e) {
    if (controller.signal.aborted) {
      throw new ApiError('응답이 너무 늦습니다. 다시 시도해 주세요.', 0)
    }
    throw e
  } finally {
    clearTimeout(timer)
  }
  if (!res.ok) {
    let message = `요청이 실패했습니다 (${res.status})`
    let reason: string | undefined
    try {
      const problem = await res.json()
      if (problem?.detail) message = problem.detail
      if (typeof problem?.reason === 'string') reason = problem.reason
    } catch {
      /* 본문이 JSON이 아니면 기본 메시지를 쓴다 */
    }
    if (res.status === 429 && reason === 'budget') window.dispatchEvent(new Event(BUDGET_EXHAUSTED))
    throw new ApiError(message, res.status, reason)
  }
  // 초기화(DELETE)는 본문 없이 204로 답한다.
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export interface ServerInfo {
  judge: string
  fakeJudge: boolean
  judgeOnline: boolean
  /** 오늘 판정 예산을 다 썼다 */
  budgetExhausted: boolean
}

export const api = {
  info: () => request<ServerInfo>('/info'),
  scenarios: () => request<ScenarioSummary[]>('/scenarios'),
  /** 이 기기에 그 사건의 판이 있으면 그 판, 없으면 새 판 */
  start: (scenario: string) => request<GameView>('/games', { scenario }),
  myGames: () => request<MyGame[]>('/games'),
  /** 초기화: 판을 숨기고 처음부터 다시 시작하게 한다(서버 DB에는 남는다) */
  reset: (id: string) => request<void>(`/games/${id}`, undefined, 'DELETE'),
  game: (id: string) => request<GameView>(`/games/${id}`),
  ask: (id: string, text: string) => request<GameView>(`/games/${id}/questions`, { text }),
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

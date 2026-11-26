-- 판정 지표와 게임 사건. 게임 판과 따로 보관한다(외래키 없음, 초기화해도 남는다).
-- 질문·추리 원문은 넣지 않는다(text_length만).
create table events (
  id bigint generated always as identity primary key,
  at timestamptz not null,
  type text not null,
  client_id uuid not null,
  game_id uuid,
  scenario text,
  request_id text,
  outcome text not null,
  answer text,
  used_after int,
  text_length int,
  total_ms int,
  backend_ms int,
  ai_ms int,
  llm_ms int,
  model text,
  input_tokens int,
  output_tokens int,
  cached_tokens int,
  app_version text not null,
  detail jsonb
);

create index events_at_idx on events (at);
create index events_client_at_idx on events (client_id, at);
create index events_type_at_idx on events (type, at);

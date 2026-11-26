-- 기기(client_id)마다 시나리오 하나에 한 판.
create table games (
  id uuid primary key,
  client_id uuid not null,
  scenario_slug text not null,
  status text not null,
  used int not null,
  seq int not null,
  keyword_found int[] not null,
  bonus_found int[] not null,
  version bigint not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (client_id, scenario_slug)
);

-- 질문·추리·힌트를 한 줄씩. 판을 지우면 함께 지워진다.
create table game_entries (
  id bigint generated always as identity primary key,
  game_id uuid not null references games(id) on delete cascade,
  seq int not null,
  kind text not null,
  no int,
  text text not null,
  answer text,
  correct boolean,
  closeness text,
  created_at timestamptz not null,
  unique (game_id, seq)
);

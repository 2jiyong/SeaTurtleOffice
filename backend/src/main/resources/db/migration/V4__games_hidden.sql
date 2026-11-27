-- 초기화는 판을 지우지 않고 숨긴다(hidden_at). 숨긴 판과 기록은 DB에 남아 판정 품질을 살필 때 쓴다.
-- 한 기기·시나리오에 판 하나라는 규칙은 숨기지 않은 판에만 건다. 숨긴 판은 몇 개든 남을 수 있다.
alter table games add column hidden_at timestamptz;
alter table games drop constraint games_client_id_scenario_slug_key;
create unique index games_client_scenario_visible_idx on games (client_id, scenario_slug) where hidden_at is null;

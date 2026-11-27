-- 사용량 표 네 개(읽기 전용). deploy/usage.sh가 서버 DB에 흘려 보낸다.
-- 날짜는 한국 시간 자정 기준(한도와 같다). 기간: psql 변수 days(기본 7).
-- 비용 = (입력-캐시)×0.45 + 캐시×0.045 + 출력×3.6, 100만 토큰당 달러(우선 처리 단가). 대상은 질문·추리(limited 제외).
-- 단가와 예산은 backend application.yml의 limits와 같다. 바꾸면 여기도 고친다.
\set ON_ERROR_STOP 1
-- 날짜 더하기(interval '1 day')가 늘 24시간이 되게 세션 시간대를 UTC로 둔다(경계는 아래에서 한국 시간으로 계산).
set time zone 'UTC';
\if :{?days}
\else
	\set days 7
\endif
-- 시험(usage-test.sh)은 tagged=1로 표마다 첫 칸에 이름을 붙여 줄을 찾는다.
\if :{?tagged}
	\set c_today '''오늘'','
	\set c_day '''일'','
	\set c_ver '''버전'','
	\set c_case '''사건'','
\else
	\set c_today ''
	\set c_day ''
	\set c_ver ''
	\set c_case ''
\endif

\echo ■ 오늘 (한국 시간)
with p as (select date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' t0),
e as (
	select ev.*,
		type in ('question_asked', 'guess_submitted') and outcome <> 'limited' judged,
		((coalesce(input_tokens, 0) - coalesce(cached_tokens, 0)) * 0.45
			+ coalesce(cached_tokens, 0) * 0.045 + coalesce(output_tokens, 0) * 3.6) / 1000000.0 cost
	from events ev, p where ev.at >= p.t0
)
select :c_today
	count(distinct client_id) "기기",
	count(*) filter (where type = 'game_started') "새 판",
	count(*) filter (where type = 'question_asked' and outcome = 'ok') "질문",
	count(*) filter (where type = 'guess_submitted' and outcome = 'ok') "추리",
	count(*) filter (where type = 'hint_used') "힌트",
	count(*) filter (where type = 'game_ended' and detail ->> 'status' = 'SOLVED') "해결",
	count(*) filter (where type = 'game_ended' and detail ->> 'status' <> 'SOLVED') "미해결",
	round(coalesce(sum(cost) filter (where judged), 0), 3) "비용($)",
	6.00 "예산($)"
from e;

\echo ■ 날짜별 (최근 :days 일, p50·p95는 성공한 판정의 전체 응답 시간)
with p as (select date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' t0, :days::int days),
d as (select generate_series(p.t0 - (p.days - 1) * interval '1 day', p.t0, interval '1 day') d from p),
e as (
	select ev.*,
		type in ('question_asked', 'guess_submitted') and outcome <> 'limited' judged,
		type in ('question_asked', 'guess_submitted') and outcome = 'ok' ok_judge,
		((coalesce(input_tokens, 0) - coalesce(cached_tokens, 0)) * 0.45
			+ coalesce(cached_tokens, 0) * 0.045 + coalesce(output_tokens, 0) * 3.6) / 1000000.0 cost
	from events ev, p where ev.at >= p.t0 - (p.days - 1) * interval '1 day'
)
select :c_day
	to_char(d.d at time zone 'Asia/Seoul', 'MM-DD') "날짜",
	count(distinct e.client_id) "기기",
	count(e.id) filter (where judged) "판정",
	round(coalesce(sum(cost) filter (where judged), 0), 3) "비용($)",
	coalesce(round(percentile_cont(0.5) within group (order by total_ms) filter (where ok_judge))::text, '-') "p50(ms)",
	coalesce(round(percentile_cont(0.95) within group (order by total_ms) filter (where ok_judge))::text, '-') "p95(ms)",
	count(e.id) filter (where outcome in ('judge_error', 'timeout')) "오류",
	count(e.id) filter (where outcome = 'limited') "한도초과"
from d left join e on e.at >= d.d and e.at < d.d + interval '1 day'
group by d.d
order by d.d desc;

\echo ■ 버전별 응답 시간 (기간 안, 성공한 판정)
with p as (select date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' t0, :days::int days)
select :c_ver
	app_version "버전",
	coalesce(model, '-') "모델",
	count(*) "판정",
	round(percentile_cont(0.5) within group (order by total_ms)) "p50(ms)",
	round(percentile_cont(0.95) within group (order by total_ms)) "p95(ms)",
	coalesce(round(percentile_cont(0.5) within group (order by llm_ms))::text, '-') "LLM p50",
	coalesce(round(avg(input_tokens))::text, '-') "평균 입력",
	coalesce(round(avg(output_tokens))::text, '-') "평균 출력"
from events, p
where at >= p.t0 - (p.days - 1) * interval '1 day'
	and type in ('question_asked', 'guess_submitted') and outcome = 'ok'
group by app_version, model
order by max(at) desc;

\echo ■ 사건별 (기간 안에 시작한 판만, 해결은 지금까지)
with p as (select date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' t0, :days::int days),
g as (
	select game_id, scenario from events, p
	where type = 'game_started' and at >= p.t0 - (p.days - 1) * interval '1 day'
),
s as (
	-- 해결은 그 판들 가운데 끝난 것(기간 밖에서 시작해 기간 안에 끝난 판은 넣지 않는다)
	select g.scenario,
		count(distinct g.game_id) started,
		count(distinct e.game_id) filter (where e.detail ->> 'status' = 'SOLVED') solved,
		avg((e.detail ->> 'used')::int) filter (where e.detail ->> 'status' = 'SOLVED') used
	from g left join events e on e.game_id = g.game_id and e.type = 'game_ended'
	group by g.scenario
)
select :c_case
	scenario "사건",
	started "시작",
	solved "해결",
	round(100.0 * solved / started) || '%' "해결률",
	coalesce(round(used)::text, '-') "평균 사용(해결)"
from s
where started > 0
order by started desc, scenario;

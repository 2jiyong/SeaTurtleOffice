-- 질문의 명제(화면에 보인다)와 질문·추리의 판정 근거(진상이 담겨 있어 화면에는 보내지 않는다).
-- 예전 기록은 둘 다 null이다.
alter table game_entries add column proposition text;
alter table game_entries add column reason text;

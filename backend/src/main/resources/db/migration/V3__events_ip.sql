-- IP 원문 대신 비밀 값을 섞은 해시(HMAC-SHA256 앞 32자). IP별 하루 판정 한도를 센다.
alter table events add column ip_hash text;
create index events_ip_at_idx on events (ip_hash, at);

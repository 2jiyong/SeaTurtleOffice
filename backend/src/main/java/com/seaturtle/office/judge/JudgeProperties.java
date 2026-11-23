package com.seaturtle.office.judge;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl AI 판정 서버 주소
 * @param timeout 판정 한 번을 기다리는 시간. LLM 응답이 느릴 수 있어 넉넉히 둔다.
 */
@ConfigurationProperties("judge")
public record JudgeProperties(String baseUrl, Duration timeout) {
}

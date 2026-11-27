package com.seaturtle.office.limit;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 판정 사용량 한도. 0 이하면 그 검사를 끈다. 하루는 zone의 자정 기준.
 * price: 100만 토큰당 달러(입력, 캐시된 입력, 출력).
 */
@ConfigurationProperties("limits")
public record LimitProperties(double dailyBudgetUsd, int devicePerDay, int ipPerDay, ZoneId zone, Price price) {

	public record Price(double input, double cachedInput, double output) {
	}
}

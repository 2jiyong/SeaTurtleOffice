package com.seaturtle.office.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** cookieSecure: 기기 쿠키에 Secure를 붙인다(HTTPS 배포에서 true). */
@ConfigurationProperties("game")
public record GameProperties(boolean cookieSecure) {
}

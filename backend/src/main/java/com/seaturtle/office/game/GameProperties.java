package com.seaturtle.office.game;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("game")
public record GameProperties(Duration idleTtl) {
}

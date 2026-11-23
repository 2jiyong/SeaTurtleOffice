package com.seaturtle.office.content;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** contentDir는 문자열로 받는다. Path로 받으면 ".."를 리소스 경로로 해석해 실패한다. */
@ConfigurationProperties("game")
public record ContentProperties(String contentDir) {

	public Path root() {
		return Path.of(contentDir).toAbsolutePath().normalize();
	}
}

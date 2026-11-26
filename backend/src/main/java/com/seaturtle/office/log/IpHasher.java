package com.seaturtle.office.log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IP를 원문 대신 HMAC-SHA256(비밀 값, IP)의 앞 32자로 바꾼다. events는 지우지 않고 쌓이므로 원문을 남기지 않는다.
 * 평범한 해시는 IPv4 전체를 대입하면 되돌릴 수 있어 비밀 값(IP_HASH_SECRET)을 섞는다.
 */
@Component
public class IpHasher {

	private final SecretKeySpec key;

	public IpHasher(@Value("${game.ip-hash-secret:dev-only}") String secret) {
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	public String hash(String ip) {
		if (ip == null) {
			return null;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			byte[] digest = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 32);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256을 쓸 수 없습니다", e);
		}
	}
}

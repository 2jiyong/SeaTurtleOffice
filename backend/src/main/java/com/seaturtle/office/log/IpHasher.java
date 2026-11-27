package com.seaturtle.office.log;

import java.net.InetAddress;
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
			byte[] digest = mac.doFinal(subject(ip).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 32);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256을 쓸 수 없습니다", e);
		}
	}

	/** IPv6는 앞 64비트(/64, 보통 한 회선)로 묶는다. 그 안에서는 주소를 사실상 무한히 바꿀 수 있기 때문이다. */
	private static String subject(String ip) {
		if (ip.indexOf(':') < 0) {
			return ip;
		}
		try {
			// IP 문자열(리터럴)이라 DNS 조회는 일어나지 않는다.
			byte[] bytes = InetAddress.ofLiteral(ip).getAddress();
			if (bytes.length == 16) {
				return "v6/64:" + HexFormat.of().formatHex(bytes, 0, 8);
			}
			return InetAddress.ofLiteral(ip).getHostAddress();
		} catch (IllegalArgumentException e) {
			return ip;
		}
	}
}

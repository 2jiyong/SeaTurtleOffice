package com.seaturtle.office.content;

import java.util.List;

/** 게임 진행에 필요한 시나리오 정보. 판정 자료(판정 메모, 세계관 항목)는 AI 서버가 따로 읽는다. */
public record Scenario(
		String slug,
		String id,
		String title,
		String difficulty,
		String problem,
		String truth,
		List<String> keyPoints,
		List<String> hints) {
}

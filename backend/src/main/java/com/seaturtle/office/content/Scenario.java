package com.seaturtle.office.content;

import java.util.List;

public record Scenario(
		String slug,
		String id,
		String title,
		String difficulty,
		List<String> uses,
		String problem,
		String truth,
		List<String> keyPoints,
		String judgeNotes,
		List<String> hints) {
}

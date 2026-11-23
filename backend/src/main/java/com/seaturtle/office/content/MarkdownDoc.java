package com.seaturtle.office.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** YAML 프런트매터와 본문으로 나눈 마크다운 문서. */
public record MarkdownDoc(Map<String, Object> meta, String body) {

	private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

	public static MarkdownDoc parse(String raw) {
		String text = raw.replace("\r\n", "\n");
		if (!text.startsWith("---\n")) {
			return new MarkdownDoc(Map.of(), text);
		}
		int end = text.indexOf("\n---\n", 4);
		if (end < 0) {
			throw new IllegalArgumentException("프런트매터가 닫히지 않았습니다");
		}
		String front = text.substring(4, end);
		Map<String, Object> meta;
		try {
			Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(front);
			@SuppressWarnings("unchecked")
			Map<String, Object> m = loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
			meta = m;
		} catch (RuntimeException e) {
			// 값에 따옴표 없는 ": "가 들어간 경우 등. 한 줄에 "키: 값" 하나라고 보고 읽는다.
			meta = parseLoosely(front);
		}
		return new MarkdownDoc(meta, text.substring(end + 5));
	}

	private static Map<String, Object> parseLoosely(String front) {
		Map<String, Object> meta = new LinkedHashMap<>();
		for (String line : front.split("\n")) {
			int colon = line.indexOf(':');
			if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
				continue;
			}
			String key = line.substring(0, colon).strip();
			String value = line.substring(colon + 1).strip();
			if (value.startsWith("[") && value.endsWith("]")) {
				String inner = value.substring(1, value.length() - 1).strip();
				meta.put(key, inner.isEmpty() ? List.of()
						: java.util.Arrays.stream(inner.split(",")).map(String::strip).toList());
			} else {
				meta.put(key, value.replaceAll("^\"(.*)\"$", "$1"));
			}
		}
		return meta;
	}

	public String string(String key) {
		Object value = meta.get(key);
		return value == null ? null : value.toString();
	}

	public List<String> list(String key) {
		Object value = meta.get(key);
		if (value instanceof List<?> l) {
			return l.stream().map(Object::toString).toList();
		}
		return value == null ? List.of() : List.of(value.toString());
	}

	/** AI에게 보낼 본문: HTML 주석을 지운다. */
	public String cleanBody() {
		return HTML_COMMENT.matcher(body).replaceAll("").strip();
	}

	/** "## 제목" 단위로 본문을 나눈다. 첫 "##" 앞의 내용은 버린다. */
	public Map<String, String> sections() {
		Map<String, String> result = new LinkedHashMap<>();
		String current = null;
		StringBuilder buf = new StringBuilder();
		for (String line : cleanBody().split("\n", -1)) {
			if (line.startsWith("## ")) {
				if (current != null) {
					result.put(current, buf.toString().strip());
				}
				current = line.substring(3).strip();
				buf.setLength(0);
			} else if (current != null) {
				buf.append(line).append('\n');
			}
		}
		if (current != null) {
			result.put(current, buf.toString().strip());
		}
		return result;
	}

	/** "- " 또는 "1. "로 시작하는 목록 항목을 뽑는다. 들여쓴 이어지는 줄은 앞 항목에 붙인다. */
	public static List<String> listItems(String section) {
		List<String> items = new ArrayList<>();
		if (section == null) {
			return items;
		}
		Pattern bullet = Pattern.compile("^(?:[-*]|\\d+\\.)\\s+(.*)$");
		for (String line : section.split("\n")) {
			var m = bullet.matcher(line);
			if (m.matches()) {
				items.add(m.group(1).strip());
			} else if (!items.isEmpty() && !line.isBlank() && Character.isWhitespace(line.charAt(0))) {
				int last = items.size() - 1;
				items.set(last, items.get(last) + " " + line.strip());
			}
		}
		return items;
	}
}

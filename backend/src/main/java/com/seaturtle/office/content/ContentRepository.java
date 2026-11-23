package com.seaturtle.office.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * scenarios/의 마크다운을 시작할 때 한 번 읽어 둔다. 세계관(world/)은 판정에만 쓰이므로 AI 서버가 읽는다.
 * 규격은 .claude/skills/namu-ingest/references/schema.md, scenarios/README.md를 따른다.
 */
@Component
public class ContentRepository {

	private static final Logger log = LoggerFactory.getLogger(ContentRepository.class);

	private final Map<String, Scenario> scenarios;

	public ContentRepository(ContentProperties props) {
		Path root = props.root();
		if (!Files.isDirectory(root.resolve("scenarios"))) {
			throw new IllegalStateException("scenarios/를 찾을 수 없습니다: " + root
					+ " (game.content-dir 또는 GAME_CONTENT_DIR를 확인하세요)");
		}
		this.scenarios = loadScenarios(root.resolve("scenarios"));
		log.info("시나리오를 읽었습니다: {} ({}개)", root, scenarios.size());
	}

	public Collection<Scenario> scenarios() {
		return scenarios.values();
	}

	public Optional<Scenario> scenario(String slug) {
		return Optional.ofNullable(scenarios.get(slug));
	}

	private static Map<String, Scenario> loadScenarios(Path dir) {
		Map<String, Scenario> result = new LinkedHashMap<>();
		List<Path> files = markdownFiles(dir, false).stream()
				.sorted(Comparator.comparing(p -> p.getFileName().toString()))
				.toList();
		for (Path path : files) {
			String slug = path.getFileName().toString().replaceFirst("\\.md$", "");
			MarkdownDoc doc = MarkdownDoc.parse(read(path));
			Map<String, String> sec = doc.sections();
			if (!sec.containsKey("문제") || !sec.containsKey("진상")) {
				log.warn("문제/진상 섹션이 없는 시나리오를 건너뜁니다: {}", path);
				continue;
			}
			result.put(slug, new Scenario(
					slug,
					doc.string("id"),
					doc.string("title"),
					doc.string("difficulty"),
					sec.get("문제"),
					sec.get("진상"),
					MarkdownDoc.listItems(sec.get("핵심 포인트")),
					MarkdownDoc.listItems(sec.get("힌트"))));
		}
		return result;
	}

	private static List<Path> markdownFiles(Path dir, boolean recursive) {
		try (Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir)) {
			List<Path> files = new ArrayList<>();
			stream.filter(Files::isRegularFile)
					.filter(p -> {
						String name = p.getFileName().toString();
						return name.endsWith(".md") && !name.startsWith("_") && !name.equals("README.md");
					})
					.forEach(files::add);
			return files;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

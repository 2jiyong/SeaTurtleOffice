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
 * world/와 scenarios/의 마크다운을 시작할 때 한 번 읽어 둔다.
 * 규격은 .claude/skills/namu-ingest/references/schema.md, scenarios/README.md를 따른다.
 */
@Component
public class ContentRepository {

	private static final Logger log = LoggerFactory.getLogger(ContentRepository.class);

	private final List<WorldDoc> base;
	private final Map<String, WorldDoc> details;
	private final Map<String, Scenario> scenarios;

	public ContentRepository(ContentProperties props) {
		Path root = props.root();
		if (!Files.isDirectory(root.resolve("world")) || !Files.isDirectory(root.resolve("scenarios"))) {
			throw new IllegalStateException("world/와 scenarios/를 찾을 수 없습니다: " + root
					+ " (game.content-dir 또는 GAME_CONTENT_DIR를 확인하세요)");
		}
		this.base = loadBase(root.resolve("world/base"));
		this.details = loadDetails(root.resolve("world/detail"));
		this.scenarios = loadScenarios(root.resolve("scenarios"));
		log.info("콘텐츠를 읽었습니다: {} (베이스 {}개, 상세 {}개, 시나리오 {}개)",
				root, base.size(), details.size(), scenarios.size());
		missingUses().forEach((slug, ids) -> log.warn("시나리오 {}의 uses에 없는 항목이 있습니다: {}", slug, ids));
	}

	public List<WorldDoc> base() {
		return base;
	}

	public Collection<Scenario> scenarios() {
		return scenarios.values();
	}

	public Optional<Scenario> scenario(String slug) {
		return Optional.ofNullable(scenarios.get(slug));
	}

	/** 시나리오의 uses 중 상세 항목만 돌려준다. 베이스 ID(base.*)는 이미 항상 들어가므로 건너뛴다. */
	public List<WorldDoc> detailsFor(Scenario scenario) {
		return scenario.uses().stream()
				.filter(id -> !id.startsWith("base."))
				.map(details::get)
				.filter(doc -> doc != null)
				.toList();
	}

	/** 시나리오별로 uses에 적혀 있지만 world/에 없는 ID. */
	public Map<String, List<String>> missingUses() {
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (Scenario s : scenarios.values()) {
			List<String> missing = s.uses().stream()
					.filter(id -> !id.startsWith("base.") && !details.containsKey(id))
					.toList();
			if (!missing.isEmpty()) {
				result.put(s.slug(), missing);
			}
		}
		return result;
	}

	private static List<WorldDoc> loadBase(Path dir) {
		return markdownFiles(dir, false).stream()
				.sorted(Comparator.comparing(p -> p.getFileName().toString()))
				.map(path -> {
					MarkdownDoc doc = MarkdownDoc.parse(read(path));
					return new WorldDoc(doc.string("id"), null, doc.cleanBody());
				})
				.toList();
	}

	private static Map<String, WorldDoc> loadDetails(Path dir) {
		Map<String, WorldDoc> result = new LinkedHashMap<>();
		for (Path path : markdownFiles(dir, true)) {
			MarkdownDoc doc = MarkdownDoc.parse(read(path));
			String id = doc.string("id");
			if (id == null) {
				log.warn("id가 없는 상세 문서를 건너뜁니다: {}", path);
				continue;
			}
			// 출처 섹션은 판정에 쓰이지 않으므로 뺀다.
			StringBuilder text = new StringBuilder();
			doc.sections().forEach((heading, content) -> {
				if (!heading.equals("출처")) {
					text.append("## ").append(heading).append('\n').append(content).append("\n\n");
				}
			});
			result.put(id, new WorldDoc(id, doc.string("title"), text.toString().strip()));
		}
		return result;
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
					doc.list("uses"),
					sec.get("문제"),
					sec.get("진상"),
					MarkdownDoc.listItems(sec.get("핵심 포인트")),
					sec.getOrDefault("판정 메모", ""),
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

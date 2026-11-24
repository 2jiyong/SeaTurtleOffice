package com.seaturtle.office.content;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 시나리오 그림을 scenarios/images/<slug>/에서 찾는다. 이름은 cover, hint-1~3만 받는다.
 * 요청할 때마다 파일을 확인해서, 서버를 다시 켜지 않아도 새로 넣은 그림이 보인다.
 */
@Component
public class ScenarioImages {

	public static final Set<String> NAMES = Set.of("cover", "hint-1", "hint-2", "hint-3");
	/** 먼저 찾는 확장자부터 */
	static final List<String> EXTENSIONS = List.of("webp", "png", "jpg");
	private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]*");

	private final Path dir;

	public ScenarioImages(ContentProperties props) {
		this.dir = props.root().resolve("scenarios/images");
	}

	public Optional<Path> find(String slug, String name) {
		// 정해진 이름과 slug 모양만 받아서 폴더 밖으로 나갈 수 없게 한다.
		if (slug == null || name == null || !SLUG.matcher(slug).matches() || !NAMES.contains(name)) {
			return Optional.empty();
		}
		for (String ext : EXTENSIONS) {
			Path file = dir.resolve(slug).resolve(name + "." + ext);
			if (Files.isRegularFile(file)) {
				return Optional.of(file);
			}
		}
		return Optional.empty();
	}

	/** 화면에 줄 주소. 그림이 없으면 null. */
	public String url(String slug, String name) {
		return find(slug, name).isPresent() ? "/api/scenarios/" + slug + "/images/" + name : null;
	}
}

package com.seaturtle.office;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.seaturtle.office.content.ContentProperties;
import com.seaturtle.office.content.ScenarioImages;

/** 이미지는 scenarios/images/<slug>/ 아래 정해진 이름만 찾는다. 경로를 벗어나는 이름은 받지 않는다. */
class ScenarioImagesTests {

	@TempDir
	Path root;

	ScenarioImages images() throws Exception {
		Path dir = Files.createDirectories(root.resolve("scenarios/images/boys-order"));
		Files.writeString(dir.resolve("cover.png"), "png");
		Files.writeString(dir.resolve("hint-2.webp"), "webp");
		Files.writeString(root.resolve("secret.txt"), "secret");
		return new ScenarioImages(new ContentProperties(root.toString()));
	}

	@Test
	void findsAllowedNamesByExtensionOrder() throws Exception {
		var images = images();
		assertThat(images.find("boys-order", "cover")).hasValueSatisfying(p -> assertThat(p.getFileName().toString()).isEqualTo("cover.png"));
		assertThat(images.find("boys-order", "hint-2")).isPresent();
		assertThat(images.find("boys-order", "hint-1")).isEmpty();
		assertThat(images.url("boys-order", "cover")).isEqualTo("/api/scenarios/boys-order/images/cover");
		assertThat(images.url("boys-order", "hint-1")).isNull();
	}

	@Test
	void rejectsPathsOutsideImageFolder() throws Exception {
		var images = images();
		assertThat(images.find("boys-order", "../x")).isEmpty();
		assertThat(images.find("boys-order", "secret")).isEmpty();
		assertThat(images.find("../..", "cover")).isEmpty();
		assertThat(images.find("..", "secret")).isEmpty();
	}

	@Test
	void doesNotFollowLinksOutsideImageFolder() throws Exception {
		var images = images();
		Path dir = root.resolve("scenarios/images");
		// 파일 링크: hint-1 → 폴더 밖 secret.txt
		Files.createSymbolicLink(dir.resolve("boys-order/hint-1.webp"), root.resolve("secret.txt"));
		// 폴더 링크: one-week → 폴더 밖
		Files.createDirectories(root.resolve("outside"));
		Files.writeString(root.resolve("outside/cover.webp"), "x");
		Files.createSymbolicLink(dir.resolve("one-week"), root.resolve("outside"));
		assertThat(images.find("boys-order", "hint-1")).isEmpty();
		assertThat(images.find("one-week", "cover")).isEmpty();
	}
}

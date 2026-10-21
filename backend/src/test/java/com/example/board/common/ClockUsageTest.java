package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-23#6 (BE-7) 운영 코드의 현재 시각은 Clock 빈 하나에서만 나온다. 시스템 시계를 직접 읽는 곳은 ClockConfiguration 뿐이다. */
class ClockUsageTest {

	private static final Pattern SYSTEM_TIME = Pattern.compile(
			"Instant\\.now\\(|Clock\\.system(UTC|DefaultZone)?\\(|(LocalDateTime|LocalDate|OffsetDateTime|ZonedDateTime)\\.now\\(|System\\.currentTimeMillis\\(");

	@Test
	@DisplayName("TASK-23#6 src/main 에 Instant.now()·Clock.systemUTC()·System.currentTimeMillis() 가 ClockConfiguration 밖에 없다")
	void onlyClockConfigurationReadsSystemTime() throws IOException {
		Path main = Path.of("src/main/java");
		assertThat(main).isDirectory();
		List<String> offenders;
		try (Stream<Path> files = Files.walk(main)) {
			offenders = files.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.getFileName().toString().equals("ClockConfiguration.java"))
				.flatMap(ClockUsageTest::offendingLines)
				.toList();
		}
		assertThat(offenders).isEmpty();
	}

	private static Stream<String> offendingLines(Path path) {
		try {
			List<String> lines = Files.readAllLines(path);
			return java.util.stream.IntStream.range(0, lines.size())
				.filter(i -> SYSTEM_TIME.matcher(lines.get(i)).find())
				.mapToObj(i -> path + ":" + (i + 1) + ": " + lines.get(i).strip());
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

}

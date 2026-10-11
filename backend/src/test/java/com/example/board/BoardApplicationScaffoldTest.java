package com.example.board;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** TASK-1#1 백엔드 스캐폴드가 빌드·테스트된다. */
class BoardApplicationScaffoldTest {

	@Test
	@DisplayName("TASK-1#1 BoardApplication은 @SpringBootApplication 진입점이다")
	void applicationClassIsSpringBootApplication() {
		assertThat(BoardApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
	}

}

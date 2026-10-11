package com.example.board.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-4#2 새 소셜 계정의 닉네임은 가입 규칙(2~20자)을 항상 만족한다. */
class NicknamesTest {

	@Test
	@DisplayName("TASK-4#2 공백 정리, 20자(코드 포인트) 자르기, 2자 미만·null 은 userNNNN")
	void normalizes() {
		assertThat(Nicknames.from("  Kim \t Chul\nSoo ")).isEqualTo("Kim Chul Soo");
		assertThat(Nicknames.from("가나다라마바사아자차카타파하가나다라마바사아")).hasSize(20);
		assertThat(Nicknames.from("😀".repeat(25)).codePointCount(0, Nicknames.from("😀".repeat(25)).length()))
			.isEqualTo(20);
		assertThat(Nicknames.from("abcdefghijklmnopqrs   tuvwxyz")).isEqualTo("abcdefghijklmnopqrs");
		assertThat(Nicknames.from("A")).matches("user\\d{4}");
		assertThat(Nicknames.from(null)).matches("user\\d{4}");
		assertThat(Nicknames.from("  ")).matches("user\\d{4}");
	}

}

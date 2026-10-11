package com.example.board.tag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 태그 (TASK-8#1). 이름은 정규화된 값(소문자, trim, 공백 1칸)만 저장한다(UNIQUE). */
@Entity
@Table(name = "tags")
public class Tag {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 30, unique = true)
	private String name;

	protected Tag() {
	}

	private Tag(String name) {
		this.name = name;
	}

	/** 이미 정규화된 이름으로 만든다. 일반 경로는 경쟁 안전 upsert(TASK-8 B3)를 쓴다. */
	public static Tag named(String normalizedName) {
		return new Tag(normalizedName);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

}

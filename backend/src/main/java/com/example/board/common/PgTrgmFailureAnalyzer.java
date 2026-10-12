package com.example.board.common;

import java.sql.SQLException;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * V8 의 {@code CREATE EXTENSION pg_trgm} 실패를 알기 쉬운 기동 실패 보고서로 바꾼다 (TASK-21#5, doc-2 BE-12).
 * {@code META-INF/spring.factories} 로 등록한다.
 *
 * <p>pg_trgm 은 PostgreSQL 13+ 에서 trusted 확장이라 슈퍼유저가 아니어도 <b>현재 DB 에 CREATE 권한</b>이 있으면 만들 수 있다(DB
 * 소유자, RDS 마스터 사용자). 앱 DB 사용자에게 그 권한이 없거나, contrib 이 없는 PostgreSQL 이면 V8 이 실패한다. 이미 적용된
 * 마이그레이션의 체크섬을 바꾸지 않도록 V8 파일은 고치지 않고, 원인과 조치를 이 보고서와 문서(docs/first-deploy.md)로 알린다.
 */
public class PgTrgmFailureAnalyzer implements FailureAnalyzer {

	static final String EXTENSION = "pg_trgm";

	@Override
	public FailureAnalysis analyze(Throwable failure) {
		if (!mentionsPgTrgm(failure)) {
			return null;
		}
		String cause = databaseMessage(failure);
		return new FailureAnalysis("DB 마이그레이션 V8__post_search_trgm.sql 의 CREATE EXTENSION " + EXTENSION
				+ " 이 실패해 기동하지 못했다" + (cause == null ? "." : ": " + cause),
				"""
						pg_trgm 은 게시판 검색의 부분 일치 인덱스에 쓴다. PostgreSQL 13+ 에서는 trusted 확장이라 현재 DB 에 CREATE 권한이 있는 \
						사용자(DB 소유자, RDS 마스터 사용자)면 만들 수 있다. 다음 중 하나를 한 뒤 다시 기동한다.
						  1) 앱 DB 사용자(DB_USERNAME)에게 CREATE 권한: GRANT CREATE ON DATABASE <db> TO <user>;
						  2) 권한 있는 계정으로 그 DB 에 미리 실행: CREATE EXTENSION IF NOT EXISTS pg_trgm;
						  3) 확장이 설치돼 있지 않은 PostgreSQL 이면 contrib 패키지를 설치한다(공식 이미지·RDS 는 기본 포함).
						자세히: docs/first-deploy.md 의 'PostgreSQL 확장(pg_trgm) 권한'.""",
				failure);
	}

	private static boolean mentionsPgTrgm(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().contains(EXTENSION)) {
				return true;
			}
		}
		return false;
	}

	/** 원인 사슬의 마지막 SQLException 메시지 첫 줄 (예: permission denied to create extension "pg_trgm"). 없으면 null. */
	private static String databaseMessage(Throwable failure) {
		String message = null;
		for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof SQLException && cause.getMessage() != null) {
				message = cause.getMessage().strip().lines().findFirst().orElse(null);
			}
		}
		return message;
	}

}

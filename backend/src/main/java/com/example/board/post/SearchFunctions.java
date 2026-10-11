package com.example.board.post;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * 검색용 HQL/Criteria 함수 (TASK-10#3). {@code META-INF/services/org.hibernate.boot.model.FunctionContributor} 로 등록한다.
 *
 * <p>{@code post_fts_match(tsv, q)} → {@code (tsv @@ websearch_to_tsquery('simple', q))}. 연산자 {@code @@} 를 그대로 써야
 * GIN 인덱스({@code posts_search_tsv_ix})를 탄다. q 는 바인드 파라미터로 넘어간다.
 */
public class SearchFunctions implements FunctionContributor {

	public static final String FTS_MATCH = "post_fts_match";

	@Override
	public void contributeFunctions(FunctionContributions contributions) {
		contributions.getFunctionRegistry()
			.registerPattern(FTS_MATCH, "(?1 @@ websearch_to_tsquery('simple', ?2))",
					contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN));
	}

}

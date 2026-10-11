package com.example.board.security;

/** 소유자(작성자)가 있는 리소스. {@link OwnershipChecker#canModify(org.springframework.security.core.Authentication, OwnedResource)} 에 넘긴다. */
@FunctionalInterface
public interface OwnedResource {

	/** 소유자 user id. 탈퇴 등으로 없으면 null. */
	Long getOwnerId();

}

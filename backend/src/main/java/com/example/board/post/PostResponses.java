package com.example.board.post;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import com.example.board.attachment.AttachmentResponses.AttachmentView;
import com.example.board.category.Category;
import com.example.board.tag.Tag;
import com.example.board.user.User;

/** 게시글 API 응답 모양 (TASK-8#2). */
public final class PostResponses {

	/** 목록 excerpt 최대 길이(code point). */
	static final int EXCERPT_LENGTH = 200;

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private PostResponses() {
	}

	public record AuthorRef(Long id, String nickname) {

		static AuthorRef of(User user) {
			return new AuthorRef(user.getId(), user.getNickname());
		}

	}

	public record CategoryRef(Long id, String slug, String name) {

		static CategoryRef of(Category category) {
			return category == null ? null : new CategoryRef(category.getId(), category.getSlug(), category.getName());
		}

	}

	/** 목록 항목. {@code deleted}·{@code hidden} 은 ADMIN 의 includeHidden 목록에서만 true 일 수 있다. */
	public record PostSummary(Long id, String title, String excerpt, AuthorRef author, CategoryRef category,
			List<String> tags, Instant createdAt, Instant updatedAt, boolean deleted, boolean hidden, long likeCount,
			long viewCount) {

		static PostSummary of(Post post) {
			return new PostSummary(post.getId(), post.getTitle(), PostResponses.excerpt(post.getContent()),
					AuthorRef.of(post.getAuthor()), CategoryRef.of(post.getCategory()), tagNames(post),
					post.getCreatedAt(), post.getUpdatedAt(), post.isDeleted(), post.isHidden(), post.getLikeCount(),
					post.getViewCount());
		}

	}

	/**
	 * 상세. {@code editable}: 보는 사람이 수정·삭제할 수 있는지(작성자 또는 ADMIN, 삭제된 글은 false). {@code likedByMe}: 보는
	 * 사람이 좋아요를 눌렀는지(익명 false, TASK-9#2). {@code viewCount}: 이번 조회가 집계됐으면 그것까지 포함(TASK-9#3).
	 * {@code attachments}: 붙은 이미지(올린 순, TASK-11#2).
	 */
	public record PostDetail(Long id, String title, String content, AuthorRef author, CategoryRef category,
			List<String> tags, Instant createdAt, Instant updatedAt, boolean editable, boolean deleted,
			boolean hidden, long likeCount, boolean likedByMe, long viewCount, List<AttachmentView> attachments) {

		static PostDetail of(Post post, boolean editable, boolean likedByMe, List<AttachmentView> attachments) {
			return new PostDetail(post.getId(), post.getTitle(), post.getContent(), AuthorRef.of(post.getAuthor()),
					CategoryRef.of(post.getCategory()), tagNames(post), post.getCreatedAt(), post.getUpdatedAt(),
					editable, post.isDeleted(), post.isHidden(), post.getLikeCount(), likedByMe, post.getViewCount(),
					attachments);
		}

		/** 이번 조회가 집계됐을 때: viewCount + 1. */
		PostDetail withCountedView() {
			return new PostDetail(id, title, content, author, category, tags, createdAt, updatedAt, editable, deleted,
					hidden, likeCount, likedByMe, viewCount + 1, attachments);
		}

	}

	static List<String> tagNames(Post post) {
		return post.getTags().stream().map(Tag::getName).sorted().toList();
	}

	/** 공백(줄바꿈 포함)을 한 칸으로 접은 본문 앞 {@value #EXCERPT_LENGTH}자. 잘랐으면 … 를 붙인다. */
	static String excerpt(String content) {
		String flat = WHITESPACE.matcher(content).replaceAll(" ").strip();
		if (flat.codePointCount(0, flat.length()) <= EXCERPT_LENGTH) {
			return flat;
		}
		return flat.substring(0, flat.offsetByCodePoints(0, EXCERPT_LENGTH)) + "…";
	}

}

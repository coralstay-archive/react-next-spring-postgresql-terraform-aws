/**
 * 게시판 입력 검증. 백엔드 Bean Validation 과 같은 규칙이다(서버가 최종 판단). 길이는 Java `String.length()`(UTF-16 단위)라
 * JS `length` 와 같다. "공백만"은 `(?s).*\S.*` 불일치(ASCII 공백 `[ \t\n\x0B\f\r]` 만 공백으로 본다).
 */

const HAS_NON_SPACE = /[^ \t\n\v\f\r]/;

export const COMMENT_MAX = 2000;

export function validateComment(content: string): string | null {
  if (!HAS_NON_SPACE.test(content)) return "댓글을 입력하세요";
  if (content.length > COMMENT_MAX) return `댓글은 1~${COMMENT_MAX}자입니다`;
  return null;
}

export const TITLE_MAX = 100;
export const CONTENT_MAX = 20000;

export interface PostInput {
  title: string;
  content: string;
}

/** 백엔드 `PostRequests.Create`: 제목 1~100자, 본문 1~20000자, 둘 다 공백만은 안 된다. */
export function validatePost(input: PostInput): Partial<Record<keyof PostInput, string>> {
  const errors: Partial<Record<keyof PostInput, string>> = {};
  if (!HAS_NON_SPACE.test(input.title)) errors.title = "제목을 입력하세요";
  else if (input.title.length > TITLE_MAX) errors.title = `제목은 1~${TITLE_MAX}자입니다`;
  if (!HAS_NON_SPACE.test(input.content)) errors.content = "본문을 입력하세요";
  else if (input.content.length > CONTENT_MAX) errors.content = `본문은 1~${CONTENT_MAX}자입니다`;
  return errors;
}

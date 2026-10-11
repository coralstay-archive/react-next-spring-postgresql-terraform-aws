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

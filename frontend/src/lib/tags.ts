import { javaStrip } from "@/lib/board-query";

/**
 * 태그 이름 정규화 — 백엔드 `TagNames.normalize` 와 같은 순서·규칙(TASK-8 B3):
 * NFC → 앞뒤 공백 제거(Java `strip`) → 안쪽 공백(Java `\s` = ASCII 공백)을 한 칸으로 → 소문자(Locale.ROOT) → 1~30자(코드 포인트).
 * 화면은 이 결과를 미리 보여 주고, 서버가 최종 판단한다.
 */

export const MAX_TAGS = 5;
export const MAX_TAG_LENGTH = 30;

const JAVA_WHITESPACE_RUN = /[ \t\n\v\f\r]+/g;

export function normalizeTag(raw: string): { name: string; error?: undefined } | { name?: undefined; error: string } {
  const name = javaStrip(raw.normalize("NFC")).replace(JAVA_WHITESPACE_RUN, " ").toLowerCase();
  if (!name) return { error: "태그가 비어 있습니다" };
  if ([...name].length > MAX_TAG_LENGTH) return { error: `태그는 ${MAX_TAG_LENGTH}자 이하여야 합니다` };
  return { name };
}

/** 정규화해서 붙인다. 이미 있으면 그대로(중복은 하나로), 새 태그로 5개를 넘으면 오류. */
export function addTag(tags: string[], raw: string): { tags: string[]; error?: string } {
  const result = normalizeTag(raw);
  if (result.error !== undefined) return { tags, error: result.error };
  if (tags.includes(result.name)) return { tags };
  if (tags.length >= MAX_TAGS) return { tags, error: `태그는 ${MAX_TAGS}개까지 붙일 수 있습니다` };
  return { tags: [...tags, result.name] };
}

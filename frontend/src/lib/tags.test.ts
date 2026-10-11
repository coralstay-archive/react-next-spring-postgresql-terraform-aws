import { describe, expect, it } from "vitest";

import { addTag, MAX_TAGS, normalizeTag } from "@/lib/tags";

// TASK-14#3 태그 입력: 서버 TagNames.normalize 와 같은 정규화 미리보기, 최대 5개.
describe("TASK-14#3 태그 정규화(서버 TagNames 미러)", () => {
  it("TASK-14#3 NFC → 앞뒤 공백 제거 → 안쪽 공백 한 칸 → 소문자", () => {
    expect(normalizeTag("  Spring \t  Boot  ")).toEqual({ name: "spring boot" });
    expect(normalizeTag("JaVa")).toEqual({ name: "java" });
    // e + 결합 악센트(NFD) → é(NFC)
    expect(normalizeTag("Café")).toEqual({ name: "café" });
    // 한글 자모(NFD) → 완성형
    expect(normalizeTag("한")).toEqual({ name: "한" });
  });

  it("TASK-14#3 Java \\s 와 같이 ASCII 공백만 접고, 앞뒤는 Java strip 규칙으로 자른다", () => {
    expect(normalizeTag("a　　b")).toEqual({ name: "a　　b" });
    expect(normalizeTag("　a b　")).toEqual({ name: "a b" });
  });

  it("TASK-14#3 비었거나 30자(코드 포인트) 초과면 오류", () => {
    expect(normalizeTag("   ").error).toBe("태그가 비어 있습니다");
    expect(normalizeTag("a".repeat(30))).toEqual({ name: "a".repeat(30) });
    expect(normalizeTag("a".repeat(31)).error).toBe("태그는 30자 이하여야 합니다");
    expect(normalizeTag("😀".repeat(30))).toEqual({ name: "😀".repeat(30) });
  });
});

describe("TASK-14#3 태그 추가", () => {
  it("TASK-14#3 정규화한 이름으로 추가하고 중복은 하나로 합친다", () => {
    expect(addTag([], " Java ")).toEqual({ tags: ["java"] });
    expect(addTag(["java"], "JAVA")).toEqual({ tags: ["java"] });
  });

  it("TASK-14#3 5개를 넘으면 오류이고 목록은 그대로다", () => {
    const five = ["a", "b", "c", "d", "e"];
    expect(MAX_TAGS).toBe(5);
    expect(addTag(five, "f")).toEqual({ tags: five, error: "태그는 5개까지 붙일 수 있습니다" });
    // 이미 있는 태그는 5개여도 오류가 아니다(합쳐지므로).
    expect(addTag(five, "A")).toEqual({ tags: five });
  });

  it("TASK-14#3 잘못된 태그는 오류", () => {
    expect(addTag(["a"], "x".repeat(31))).toEqual({ tags: ["a"], error: "태그는 30자 이하여야 합니다" });
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";

import { clearAllDrafts, clearDraft, DRAFT_MAX_CHARS, DRAFT_MAX_ENTRIES, draftKeys, loadDraft, saveDraft } from "@/lib/drafts";

describe("TASK-29#3 초안 저장소(sessionStorage)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
  });

  it("TASK-29#3 키는 화면·글마다 다르다", () => {
    expect(draftKeys.newPost).toBe("draft:post:new");
    expect(draftKeys.editPost(3)).toBe("draft:post:edit:3");
    expect(draftKeys.comment(3)).toBe("draft:comment:3");
    expect(draftKeys.comment(3)).not.toBe(draftKeys.comment(4));
  });

  it("TASK-29#3 TASK-29#B1 저장한 사용자에게만 되돌려 준다", () => {
    expect(saveDraft(draftKeys.comment(1), 7, { content: "쓰던 댓글" })).toBe(true);
    expect(loadDraft(draftKeys.comment(1), 7)).toEqual({ content: "쓰던 댓글" });
    expect(loadDraft(draftKeys.comment(1), 8)).toBeNull();
    expect(loadDraft(draftKeys.comment(2), 7)).toBeNull();
  });

  it("TASK-29#3 clearDraft 는 그 키를, 사용자를 주면 그 사용자의 초안일 때만 지운다", () => {
    saveDraft(draftKeys.newPost, 7, { title: "a" });
    clearDraft(draftKeys.newPost, 8);
    expect(loadDraft(draftKeys.newPost, 7)).toEqual({ title: "a" });
    clearDraft(draftKeys.newPost, 7);
    expect(loadDraft(draftKeys.newPost, 7)).toBeNull();
    saveDraft(draftKeys.newPost, 7, { title: "b" });
    clearDraft(draftKeys.newPost);
    expect(loadDraft(draftKeys.newPost, 7)).toBeNull();
  });

  it("TASK-29#3 clearAllDrafts 는 초안 키만 지운다", () => {
    window.sessionStorage.setItem("auth.next", "/x");
    saveDraft(draftKeys.newPost, 7, { title: "a" });
    saveDraft(draftKeys.comment(1), 7, { content: "b" });
    clearAllDrafts();
    expect(loadDraft(draftKeys.newPost, 7)).toBeNull();
    expect(loadDraft(draftKeys.comment(1), 7)).toBeNull();
    expect(window.sessionStorage.getItem("auth.next")).toBe("/x");
  });

  it("TASK-29#3 직렬화가 상한을 넘으면 저장하지 않고 이전 초안도 지운다", () => {
    saveDraft(draftKeys.newPost, 7, { content: "짧음" });
    expect(saveDraft(draftKeys.newPost, 7, { content: "가".repeat(DRAFT_MAX_CHARS) })).toBe(false);
    expect(loadDraft(draftKeys.newPost, 7)).toBeNull();
  });

  it("TASK-29#3 초안은 최대 개수만 남기고 오래된 것부터 지운다", () => {
    const now = vi.spyOn(Date, "now");
    for (let i = 0; i < DRAFT_MAX_ENTRIES + 3; i++) {
      now.mockReturnValue(1_000 + i);
      saveDraft(draftKeys.comment(i), 7, { content: String(i) });
    }
    const keys = Object.keys(window.sessionStorage).filter((key) => key.startsWith("draft:"));
    expect(keys).toHaveLength(DRAFT_MAX_ENTRIES);
    expect(loadDraft(draftKeys.comment(0), 7)).toBeNull();
    expect(loadDraft(draftKeys.comment(2), 7)).toBeNull();
    expect(loadDraft(draftKeys.comment(3), 7)).toEqual({ content: "3" });
  });

  it("TASK-29#3 깨진 값·저장소 오류는 조용히 무시한다(입력은 화면에 남는다)", () => {
    window.sessionStorage.setItem(draftKeys.newPost, "{not json");
    expect(loadDraft(draftKeys.newPost, 7)).toBeNull();
    window.sessionStorage.setItem(draftKeys.newPost, JSON.stringify({ v: 2, userId: 7, data: {} }));
    expect(loadDraft(draftKeys.newPost, 7)).toBeNull();

    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    expect(saveDraft(draftKeys.comment(1), 7, { content: "x" })).toBe(false);
  });
});

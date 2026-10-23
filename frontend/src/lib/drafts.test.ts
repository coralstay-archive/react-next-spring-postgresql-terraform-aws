import { afterEach, describe, expect, it, vi } from "vitest";

import { clearAllDrafts, clearDraft, clearDraftsOf, DRAFT_MAX_CHARS, DRAFT_MAX_ENTRIES, draftKeys, loadDraft, saveDraft } from "@/lib/drafts";

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

  describe("TASK-29#3 저장소 용량 초과(QuotaExceededError)", () => {
    /** 값 길이 합이 `capacity` 를 넘으면 QuotaExceededError 를 던지는 가짜 sessionStorage. */
    function fakeStorage(capacity: number) {
      const map = new Map<string, string>();
      const used = () => [...map.values()].reduce((sum, value) => sum + value.length, 0);
      const storage = {
        get length() {
          return map.size;
        },
        key: (i: number) => [...map.keys()][i] ?? null,
        getItem: (key: string) => map.get(key) ?? null,
        setItem: (key: string, value: string) => {
          if (used() - (map.get(key)?.length ?? 0) + value.length > capacity) {
            throw new DOMException("full", "QuotaExceededError");
          }
          map.set(key, value);
        },
        removeItem: (key: string) => void map.delete(key),
        clear: () => map.clear(),
      } as Storage;
      vi.spyOn(window, "sessionStorage", "get").mockReturnValue(storage);
      return storage;
    }

    it("TASK-29#3 TASK-29#B1 새 버전을 못 쓰면 옛 버전을 남기지 않는다(나중에 옛 내용이 최신처럼 복원되지 않게)", () => {
      fakeStorage(300);
      expect(saveDraft(draftKeys.newPost, 7, { content: "옛 내용" })).toBe(true);
      expect(saveDraft(draftKeys.newPost, 7, { content: "새".repeat(400) })).toBe(false);
      expect(loadDraft(draftKeys.newPost, 7)).toBeNull();
    });

    it("TASK-29#3 옛 버전을 지워 자리가 나면 한 번 더 써서 새 버전을 남긴다", () => {
      fakeStorage(200);
      saveDraft(draftKeys.newPost, 7, { content: "가".repeat(80) });
      // 옛 버전(약 130자)과 새 버전(약 110자)을 함께 둘 자리는 없지만 새 버전 하나는 들어간다.
      expect(saveDraft(draftKeys.newPost, 7, { content: "나".repeat(60) })).toBe(true);
      expect(loadDraft(draftKeys.newPost, 7)).toEqual({ content: "나".repeat(60) });
    });

    it("TASK-29#3 개수 상한 정리를 쓰기 전에 해서, 가득 찬 저장소에도 새 초안이 들어간다", () => {
      const entry = (i: number) => JSON.stringify({ v: 1, userId: 7, savedAt: 1_000 + i, data: { content: "x" } }).length;
      const storage = fakeStorage(entry(10) * DRAFT_MAX_ENTRIES);
      const now = vi.spyOn(Date, "now");
      for (let i = 0; i < DRAFT_MAX_ENTRIES; i++) {
        now.mockReturnValue(1_000 + i);
        expect(saveDraft(draftKeys.comment(10 + i), 7, { content: "x" })).toBe(true);
      }
      now.mockReturnValue(2_000);
      expect(saveDraft(draftKeys.comment(99), 7, { content: "x" })).toBe(true);
      expect(storage.length).toBe(DRAFT_MAX_ENTRIES);
      expect(loadDraft(draftKeys.comment(10), 7)).toBeNull();
      expect(loadDraft(draftKeys.comment(99), 7)).toEqual({ content: "x" });
    });
  });

  it("TASK-29#3 clearDraftsOf 는 그 사용자의 초안만 지운다(계정이 바뀌었을 때)", () => {
    saveDraft(draftKeys.newPost, 7, { title: "a" });
    saveDraft(draftKeys.comment(1), 7, { content: "b" });
    saveDraft(draftKeys.comment(2), 8, { content: "c" });
    window.sessionStorage.setItem("auth.next", "/x");
    clearDraftsOf(7);
    expect(loadDraft(draftKeys.newPost, 7)).toBeNull();
    expect(loadDraft(draftKeys.comment(1), 7)).toBeNull();
    expect(loadDraft(draftKeys.comment(2), 8)).toEqual({ content: "c" });
    expect(window.sessionStorage.getItem("auth.next")).toBe("/x");
  });
});

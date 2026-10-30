import { QueryClientProvider } from "@tanstack/react-query";
import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { DRAFT_SAVE_DELAY, useDraftAutosave } from "@/hooks/use-draft-autosave";
import { draftKeys, loadDraft } from "@/lib/drafts";
import { createQueryClient, LOGGED_OUT_KEY, queryKeys } from "@/lib/query";
import { makeMe } from "@/test/utils";

const KEY = draftKeys.comment(1);

function setup(initial: { content: string; ownerId?: number }) {
  const client = createQueryClient();
  client.setQueryData(queryKeys.me, makeMe({ id: 1 }));
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const hook = renderHook(
    ({ content, ownerId }: { content: string; ownerId?: number }) =>
      useDraftAutosave({ key: KEY, ownerId: ownerId ?? 1, data: { content }, empty: !content }),
    { wrapper, initialProps: initial },
  );
  return { client, ...hook };
}

describe("TASK-29#3 초안 자동 저장(디바운스)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("TASK-29#3 입력할 때마다가 아니라 멈춘 뒤 한 번 저장한다", () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const { rerender } = setup({ content: "a" });
    rerender({ content: "ab" });
    rerender({ content: "abc" });
    expect(setItem).not.toHaveBeenCalled();
    expect(loadDraft(KEY, 1)).toBeNull();

    act(() => vi.advanceTimersByTime(DRAFT_SAVE_DELAY));
    expect(setItem).toHaveBeenCalledTimes(1);
    expect(loadDraft(KEY, 1)).toEqual({ content: "abc" });
  });

  it("TASK-29#3 TASK-29#B1 언마운트·pagehide 때 남은 저장을 바로 한다", () => {
    const first = setup({ content: "떠나기 직전" });
    first.unmount();
    expect(loadDraft(KEY, 1)).toEqual({ content: "떠나기 직전" });

    window.sessionStorage.clear();
    const second = setup({ content: "탭 닫기 직전" });
    window.dispatchEvent(new Event("pagehide"));
    expect(loadDraft(KEY, 1)).toEqual({ content: "탭 닫기 직전" });
    second.unmount();
  });

  it("TASK-29#3 flush 는 기다리지 않고 저장하고, discard 는 남은 저장을 버리고 초안을 지운다", () => {
    const { result, rerender, unmount } = setup({ content: "제출 직전" });
    act(() => result.current.flush());
    expect(loadDraft(KEY, 1)).toEqual({ content: "제출 직전" });

    rerender({ content: "제출 직전 더" });
    act(() => result.current.discard());
    unmount();
    act(() => vi.advanceTimersByTime(DRAFT_SAVE_DELAY));
    expect(loadDraft(KEY, 1)).toBeNull();
  });

  it("TASK-29#3 내용이 비면 남은 저장을 버리고 바로 지운다", () => {
    const { rerender } = setup({ content: "x" });
    act(() => vi.advanceTimersByTime(DRAFT_SAVE_DELAY));
    rerender({ content: "" });
    expect(loadDraft(KEY, 1)).toBeNull();
    act(() => vi.advanceTimersByTime(DRAFT_SAVE_DELAY));
    expect(loadDraft(KEY, 1)).toBeNull();
  });

  it("TASK-29#1 TASK-29#3 지금 사용자가 초안 주인과 다르면(A→B) A 의 입력을 저장하지 않는다", () => {
    const { client, unmount } = setup({ content: "A 가 쓰던 글" });
    act(() => client.setQueryData(queryKeys.me, makeMe({ id: 2 })));
    unmount();
    act(() => vi.advanceTimersByTime(DRAFT_SAVE_DELAY));
    expect(loadDraft(KEY, 1)).toBeNull();
    expect(loadDraft(KEY, 2)).toBeNull();
  });

  it("TASK-29#5 명시적 로그아웃 뒤에는 언마운트돼도 초안을 다시 만들지 않는다", () => {
    const { client, unmount } = setup({ content: "로그아웃 직전" });
    act(() => {
      client.setQueryData(LOGGED_OUT_KEY, true);
      client.setQueryData(queryKeys.me, null);
    });
    unmount();
    expect(loadDraft(KEY, 1)).toBeNull();
  });

  it("TASK-29#3 TASK-29#B1 세션이 끝난(me=null) 동안에는 저장한다", () => {
    const { client } = setup({ content: "만료 중 입력" });
    act(() => client.setQueryData(queryKeys.me, null));
    act(() => vi.advanceTimersByTime(DRAFT_SAVE_DELAY));
    expect(loadDraft(KEY, 1)).toEqual({ content: "만료 중 입력" });
  });
});

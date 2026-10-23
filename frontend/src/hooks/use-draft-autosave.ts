"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef } from "react";

import type { Me } from "@/lib/auth";
import { clearDraft, saveDraft } from "@/lib/drafts";
import { LOGGED_OUT_KEY, queryKeys } from "@/lib/query";

/** 입력이 멈춘 뒤 이만큼 지나면 저장한다(키 입력마다 직렬화·정리·JSON.parse 를 하지 않게). */
export const DRAFT_SAVE_DELAY = 400;

/**
 * 초안 자동 저장(TASK-29#3, B1). `data` 가 바뀌면 {@link DRAFT_SAVE_DELAY} 뒤에 한 번 저장하고, `empty`(빈 입력·원래 값과 같음)면
 * 남은 저장을 버리고 바로 지운다. 언마운트·`pagehide`(탭 닫기·이동) 때 남은 저장을 바로 한다. 제출 직전에는 `flush()`, 등록 성공·초안
 * 버리기에는 `discard()`(남은 저장을 버리고 지움)를 부른다.
 *
 * 초안 주인은 `ownerId`(이 화면을 연 사용자)다. 쓰기 직전에 다시 확인해 다음이면 쓰지 않는다:
 * - 지금 me 가 다른 사용자(계정이 A→B 로 바뀜, TASK-29#1) — A 의 입력이 B 이름으로 남지 않게.
 * - 명시적 로그아웃 뒤(TASK-29#5) — 로그아웃이 지운 초안을 언마운트 저장이 되살리지 않게.
 * 세션이 끝나 me 가 null 인 동안에는 쓴다(그때 남기는 것이 초안의 목적이다).
 *
 * `saveFailed()`(TASK-31#7): 남은 저장을 바로 하고, 마지막 저장이 실패했는지(저장 공간 없음·너무 큼) 돌려준다. 이탈 경고가 "초안에 남으니
 * 묻지 않는다"를 믿어도 되는지 본다.
 */
export function useDraftAutosave({
  key,
  ownerId,
  data,
  empty,
}: {
  key: string;
  ownerId: number | undefined;
  data: unknown;
  empty: boolean;
}) {
  const client = useQueryClient();
  const json = JSON.stringify(data);
  const pending = useRef<(() => void) | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const failed = useRef(false);

  const cancel = useCallback(() => {
    clearTimeout(timer.current);
    pending.current = null;
  }, []);

  const flush = useCallback(() => {
    clearTimeout(timer.current);
    const write = pending.current;
    pending.current = null;
    write?.();
  }, []);

  useEffect(() => {
    if (ownerId === undefined) return;
    if (empty) {
      cancel();
      clearDraft(key, ownerId);
      failed.current = false;
      return;
    }
    pending.current = () => {
      if (client.getQueryData(LOGGED_OUT_KEY)) return;
      const me = client.getQueryData<Me | null>(queryKeys.me);
      if (me && me.id !== ownerId) return;
      failed.current = !saveDraft(key, ownerId, JSON.parse(json));
    };
    clearTimeout(timer.current);
    timer.current = setTimeout(flush, DRAFT_SAVE_DELAY);
  }, [client, key, ownerId, empty, json, cancel, flush]);

  useEffect(() => {
    window.addEventListener("pagehide", flush);
    return () => {
      window.removeEventListener("pagehide", flush);
      flush();
    };
  }, [flush]);

  const discard = useCallback(() => {
    cancel();
    clearDraft(key);
  }, [cancel, key]);

  const saveFailed = useCallback(() => {
    flush();
    return failed.current;
  }, [flush]);

  return { flush, discard, saveFailed };
}

"use client";

import { onlineManager } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

const subscribe = (onChange: () => void) => onlineManager.subscribe(onChange);
const getOnline = () => onlineManager.isOnline();
const getServerOnline = () => true;

/**
 * 온라인 상태(TASK-30#1, FE-4). TanStack `onlineManager` 를 그대로 쓴다 — 브라우저 `online`·`offline` 이벤트로 바뀌고,
 * 쿼리의 일시정지·재개도 같은 값을 본다. 처음 값은 온라인이다(v5 기본: `navigator.onLine` 은 크로미움에서 거짓 오프라인이 있어 읽지 않는다).
 */
export function useOnline(): boolean {
  return useSyncExternalStore(subscribe, getOnline, getServerOnline);
}

/**
 * 오프라인 안내(TASK-30#1). 목록·글 같은 조회는 연결되면 저절로 다시 받지만, 등록·저장 같은 쓰기는 오프라인이면 바로 실패하고
 * 연결된 뒤에도 몰래 다시 보내지 않으므로(B1) 사용자가 직접 다시 시도해야 한다고 알린다.
 */
export function OfflineBanner() {
  const online = useOnline();
  if (online) return null;
  return (
    <div className="mx-auto w-full max-w-5xl px-4 pt-4" data-testid="offline-banner">
      <Alert>
        <AlertTitle>오프라인입니다</AlertTitle>
        <AlertDescription>
          인터넷 연결을 확인하세요. 목록·글은 연결되면 다시 불러오지만, 등록·저장은 연결된 뒤 직접 다시 시도해야 합니다.
        </AlertDescription>
      </Alert>
    </div>
  );
}

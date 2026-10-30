"use client";

import { useEffect, useRef } from "react";

/**
 * 페이지 이탈 경고(TASK-31#7, FE-28).
 *
 * - `unload`: true 면 탭 닫기·새로고침·주소 이동(`beforeunload`)에 브라우저 기본 확인을 띄운다(문구는 브라우저가 정한다).
 * - `inAppMessage()`: 앱 안 링크를 누를 때마다 부른다. 문장을 돌려주면 `window.confirm` 으로 묻고, 거절하면 이동을 막는다. null 이면 묻지 않는다.
 *   App Router 에는 이동을 막는 라우터 이벤트가 없어서, document 의 capture 단계 click 에서 같은 오리진 `<a href>`(next/link 포함)를 본다 —
 *   React 루트보다 먼저 받으므로 거절하면 preventDefault + stopPropagation 으로 next/link 의 라우터 이동까지 막는다.
 *   새 탭(target·수정키·가운데 버튼)·다운로드·같은 페이지 `#` 이동은 이 페이지를 떠나지 않으니 묻지 않는다.
 * - 반환하는 `confirmLeave()`: 코드로 이동하기 전(취소 버튼) 같은 규칙으로 묻는다. true 면 떠나도 된다.
 *
 * 한계: 브라우저 뒤로·앞으로(popstate)와 `router.push` 를 직접 부르는 이동은 막지 못한다(App Router 에 막는 API 가 없다).
 */
export function useLeaveGuard({ unload, inAppMessage }: { unload: boolean; inAppMessage: () => string | null }) {
  const message = useRef(inAppMessage);
  useEffect(() => {
    message.current = inAppMessage;
  });

  useEffect(() => {
    if (!unload) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // 오래된 브라우저(Chrome 119 이전 등)는 returnValue 를 봐야 묻는다.
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [unload]);

  useEffect(() => {
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0) return;
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const anchor = event.target instanceof Element ? event.target.closest("a[href]") : null;
      if (!(anchor instanceof HTMLAnchorElement)) return;
      if ((anchor.target && anchor.target !== "_self") || anchor.hasAttribute("download")) return;
      const url = new URL(anchor.href, window.location.href);
      if (url.origin !== window.location.origin) return;
      if (url.pathname === window.location.pathname && url.search === window.location.search && url.hash) return;
      const text = message.current();
      if (text === null || window.confirm(text)) return;
      event.preventDefault();
      event.stopPropagation();
    };
    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, []);

  return {
    confirmLeave: () => {
      const text = message.current();
      return text === null || window.confirm(text);
    },
  };
}

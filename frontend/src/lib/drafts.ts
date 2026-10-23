/**
 * 작성 중 초안 보존(TASK-29#3, B1: 세션이 끝나도 사용자가 입력한 글·댓글 내용은 사라지지 않는다).
 *
 * - 저장소: sessionStorage(탭 단위). 탭을 닫으면 사라지고 다른 탭과 섞이지 않는다. 다시 로그인하러 같은 탭에서 `/login/` 에 갔다가
 *   돌아오면 그대로 남아 있다.
 * - 키: 글 작성 `draft:post:new`, 글 수정 `draft:post:edit:<글 id>`, 댓글 `draft:comment:<글 id>`.
 * - 값: `{v:1, userId, savedAt, data}`. 저장한 사용자에게만 되돌려 준다(같은 탭에서 다른 계정으로 로그인하면 복원하지 않는다).
 * - 상한: 직렬화 {@link DRAFT_MAX_CHARS}자를 넘으면 저장하지 않고 그 키의 이전 초안도 지운다(입력은 화면에 남는다). 초안은
 *   {@link DRAFT_MAX_ENTRIES}개까지 남기고 오래된 것부터 지운다. 저장소를 못 쓰면(사생활 모드·용량 초과) 조용히 건너뛴다.
 * - 지우는 때: 등록·저장 성공, 입력이 비었거나 원래 값과 같아졌을 때, 초안 버리기, 명시적 로그아웃 성공(TASK-29#5).
 */

const PREFIX = "draft:";
const VERSION = 1;

/** 글 본문 상한(20000자) + 제목·태그·첨부 10개를 넉넉히 담는 크기. 이보다 크면 비정상 입력으로 본다. */
export const DRAFT_MAX_CHARS = 200_000;
export const DRAFT_MAX_ENTRIES = 20;

export const draftKeys = {
  newPost: `${PREFIX}post:new`,
  editPost: (postId: number) => `${PREFIX}post:edit:${postId}`,
  comment: (postId: number) => `${PREFIX}comment:${postId}`,
};

interface Envelope {
  v: number;
  userId: number;
  savedAt: number;
  data: unknown;
}

function store(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

function read(storage: Storage, key: string): Envelope | null {
  try {
    const raw = storage.getItem(key);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return null;
    const envelope = parsed as Envelope;
    if (envelope.v !== VERSION || typeof envelope.userId !== "number" || !("data" in envelope)) return null;
    return envelope;
  } catch {
    return null;
  }
}

function draftKeysIn(storage: Storage): string[] {
  const keys: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key?.startsWith(PREFIX)) keys.push(key);
  }
  return keys;
}

function prune(storage: Storage, keep: string) {
  const entries = draftKeysIn(storage)
    .filter((key) => key !== keep)
    .map((key) => ({ key, savedAt: read(storage, key)?.savedAt ?? 0 }));
  const excess = entries.length + 1 - DRAFT_MAX_ENTRIES;
  if (excess <= 0) return;
  entries.sort((a, b) => a.savedAt - b.savedAt);
  for (const { key } of entries.slice(0, excess)) storage.removeItem(key);
}

/** 초안 저장. 저장했으면 true, 상한 초과·저장소 오류면 false. */
export function saveDraft(key: string, userId: number, data: unknown): boolean {
  const storage = store();
  if (!storage) return false;
  try {
    const raw = JSON.stringify({ v: VERSION, userId, savedAt: Date.now(), data } satisfies Envelope);
    if (raw.length > DRAFT_MAX_CHARS) {
      storage.removeItem(key);
      return false;
    }
    storage.setItem(key, raw);
    prune(storage, key);
    return true;
  } catch {
    return false;
  }
}

/** 그 사용자가 저장한 초안의 data. 없거나 다른 사용자·깨진 값이면 null(모양 검사는 호출자가 한다). */
export function loadDraft<T>(key: string, userId: number): T | null {
  const storage = store();
  if (!storage) return null;
  const envelope = read(storage, key);
  if (!envelope || envelope.userId !== userId) return null;
  return envelope.data as T;
}

/** 초안 삭제. `userId` 를 주면 그 사용자의 초안일 때만 지운다(다른 계정의 초안을 건드리지 않는다). */
export function clearDraft(key: string, userId?: number) {
  const storage = store();
  if (!storage) return;
  try {
    if (userId !== undefined) {
      const envelope = read(storage, key);
      if (envelope && envelope.userId !== userId) return;
    }
    storage.removeItem(key);
  } catch {
    // 저장소를 못 쓰면 지울 것도 없다.
  }
}

/** 모든 초안 삭제(명시적 로그아웃). 다른 sessionStorage 값(`auth.next` 등)은 두다. */
export function clearAllDrafts() {
  const storage = store();
  if (!storage) return;
  try {
    for (const key of draftKeysIn(storage)) storage.removeItem(key);
  } catch {
    // 무시
  }
}

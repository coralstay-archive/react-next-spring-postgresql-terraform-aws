"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type ChangeEvent, type FormEvent, type KeyboardEvent } from "react";

import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useDraftAutosave } from "@/hooks/use-draft-autosave";
import { useLeaveGuard } from "@/hooks/use-leave-guard";
import { api, isApiError } from "@/lib/api";
import { useSessionUser } from "@/lib/auth";
import { useCategories, type Attachment, type PostDetail } from "@/lib/board";
import { CONTENT_MAX, TITLE_MAX, validatePost, type PostInput } from "@/lib/board-validation";
import { draftKeys, loadDraft } from "@/lib/drafts";
import { errorMessage } from "@/lib/errors";
import { queryKeys } from "@/lib/query";
import { addTag, MAX_TAGS, normalizeTag } from "@/lib/tags";
import {
  ALLOWED_IMAGE_TYPES,
  confirmAttachment,
  isRetryableConfirmError,
  isRetryableConfirmFailure,
  MAX_ATTACHMENTS,
  uploadImage,
  validateImageFile,
} from "@/lib/uploads";

type UploadItem =
  | { key: string; name: string; status: "uploading"; progress: number }
  | { key: string; name: string; status: "done"; attachment: Attachment }
  // confirmId: 올리기는 됐고 confirm 만 다시 하면 되는 첨부(TASK-31#6).
  | { key: string; name: string; status: "error"; error: string; confirmId?: number };

/**
 * 글 초안(TASK-29#3). 업로드가 끝난 첨부만 담는다(진행 중·실패한 것은 다시 올려야 한다).
 * 수정 초안은 바탕이 된 글의 `updatedAt`(`baseUpdatedAt`)도 담아, 복원할 때 서버 글이 그사이 바뀌었는지 본다.
 */
interface PostDraft {
  title: string;
  content: string;
  categorySlug: string;
  tags: string[];
  attachments: Attachment[];
  baseUpdatedAt?: string;
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string");
}

function readPostDraft(key: string, userId: number): PostDraft | null {
  const draft = loadDraft<Partial<PostDraft>>(key, userId);
  if (!draft || typeof draft !== "object") return null;
  if (typeof draft.title !== "string" || typeof draft.content !== "string") return null;
  return {
    title: draft.title,
    content: draft.content,
    categorySlug: typeof draft.categorySlug === "string" ? draft.categorySlug : "",
    tags: isStringArray(draft.tags) ? draft.tags : [],
    attachments: Array.isArray(draft.attachments)
      ? draft.attachments.filter((a): a is Attachment => !!a && typeof a === "object" && typeof a.id === "number")
      : [],
    baseUpdatedAt: typeof draft.baseUpdatedAt === "string" ? draft.baseUpdatedAt : undefined,
  };
}

/** 수정 초안의 바탕 글보다 서버 글이 새로운가. 수정 시각을 모르는 초안(이전 형식·깨진 값)도 새로운 것으로 본다. */
function serverIsNewer(post: PostDetail, draft: PostDraft): boolean {
  const base = draft.baseUpdatedAt ? Date.parse(draft.baseUpdatedAt) : NaN;
  return Number.isNaN(base) || Date.parse(post.updatedAt) > base;
}

function initialDraft(post?: PostDetail): PostDraft {
  return {
    title: post?.title ?? "",
    content: post?.content ?? "",
    categorySlug: post?.category?.slug ?? "",
    tags: post?.tags ?? [],
    attachments: post?.attachments ?? [],
  };
}

function sameDraft(a: PostDraft, b: PostDraft): boolean {
  return (
    a.title === b.title &&
    a.content === b.content &&
    a.categorySlug === b.categorySlug &&
    a.tags.join("\n") === b.tags.join("\n") &&
    a.attachments.map((x) => x.id).join(",") === b.attachments.map((x) => x.id).join(",")
  );
}

function doneItems(attachments: Attachment[]): UploadItem[] {
  return attachments.map((attachment) => ({
    key: `existing-${attachment.id}`,
    name: attachment.url,
    status: "done" as const,
    attachment,
  }));
}

type FieldErrors = Partial<Record<keyof PostInput | "category" | "tags" | "attachments", string>>;

/** 서버 `fields` 키(본문 경로)의 첫 이름 → 편집기 칸. `tags[1]`·`attachmentIds[0]` 처럼 원소 경로도 그 칸이다. */
const FIELD_OF: Record<string, keyof FieldErrors> = {
  title: "title",
  content: "content",
  categorySlug: "category",
  tags: "tags",
  attachmentIds: "attachments",
};

/**
 * 서버 필드 오류를 칸에 붙인다(TASK-31#3, FE-24). 폼에 없는 키(오타 필드 `titel` 등)는 `unmapped` 로 돌려 폼 위 알림에 보인다 —
 * 아무 안내 없이 저장만 실패하지 않게. `attachmentIds[n]` 은 n 번째로 보낸 첨부라 `attachmentIndexes` 로도 돌려준다(TASK-31#4).
 */
function mapFieldErrors(fields: Record<string, string>) {
  const mapped: FieldErrors = {};
  const unmapped: string[] = [];
  const attachmentIndexes = new Map<number, string>();
  for (const [key, message] of Object.entries(fields)) {
    const field = FIELD_OF[key.split(/[[.]/)[0]];
    if (!field) {
      unmapped.push(message);
      continue;
    }
    mapped[field] ??= message;
    const index = field === "attachments" ? /^attachmentIds\[(\d+)\]/.exec(key)?.[1] : undefined;
    if (index !== undefined) attachmentIndexes.set(Number(index), message);
  }
  return { mapped, unmapped, attachmentIndexes };
}

/** 글 저장 때 첨부 연결이 거절된 코드(TASK-31#4, FE-25). 문제 첨부를 편집기에서 표시하고 그것만 빼고 다시 저장하게 한다. */
const ATTACHMENT_LINK_ERRORS = [
  "unknown_attachment",
  "attachment_not_confirmed",
  "attachment_in_use",
  "attachment_expired",
  "attachment_not_found",
  "attachment_forbidden",
];

/**
 * 첨부 연결 오류의 문제 첨부 id. 서버는 구조화된 id 를 주지 않고 메시지 끝에 붙인다(`없는 첨부입니다: 101`) — 보낸 id 중 하나일 때만 믿는다.
 * 찾지 못하면(attachment_forbidden 등) null.
 */
function linkErrorAttachmentId(message: string, sentIds: number[]): number | null {
  const match = /(\d+)\s*$/.exec(message);
  const id = match ? Number(match[1]) : NaN;
  return sentIds.includes(id) ? id : null;
}

const TEXTAREA =
  "border-input min-h-64 w-full rounded-lg border bg-transparent px-2.5 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive";
const SELECT = "border-input h-8 w-full rounded-lg border bg-transparent px-2 text-sm sm:w-64";

/**
 * 글 작성·수정 폼 (TASK-14#3). `post` 가 있으면 수정(PATCH, 보낸 필드만 바뀐다)이다.
 * - 카테고리: `adminOnly` 는 ADMIN 에게만 보인다(서버도 403 `category_admin_only`).
 * - 태그: 서버와 같은 정규화를 미리 보여 주고 최대 5개.
 * - 이미지: 선검증 → presign → PUT(진행률) → confirm 한 첨부의 id 를 `attachmentIds` 로 보낸다. 업로드 중에는 등록을 막는다.
 * - 초안(TASK-29#3, B1): 입력이 멈추면 sessionStorage 에 저장하고(원래 값과 같으면 지움, useDraftAutosave), 다시 열면 복원한다.
 *   수정 초안은 바탕 글의 updatedAt 을 담고, 복원 때 서버 글이 더 새로우면 경고하고 초안 유지·버리기(최신 글)를 고르게 한다. 세션이 끝나도
 *   편집기는 언마운트되지 않는다(RequireAuth). 저장에 성공하면 초안을 지운다.
 * - 견고성(TASK-31): 제출은 ref 로 잠그고 성공하면 replace 로 상세에 간다(PRG, 버튼 비활성 유지). 서버 필드 오류 키를 칸에 붙이고 남은 키는
 *   폼 위에, 첨부 연결 오류는 문제 첨부에 표시하고 빼고 다시 저장. 업로드는 정체 30초·전체 5분·취소·언마운트 abort, confirm 만 다시 시도.
 *   이탈 경고(useLeaveGuard)와 히스토리 없는 취소의 / 이동. 카테고리 목록 오류 표시와 지금 카테고리 유지.
 */
export function PostEditor({ post }: { post?: PostDetail }) {
  const ids = useId();
  const router = useRouter();
  const client = useQueryClient();
  // 세션이 끝나도 마지막 사용자로 판단한다(카테고리 노출·초안 주인).
  const { user } = useSessionUser();
  const categories = useCategories();
  const isAdmin = user?.role === "ADMIN";
  // 초안 주인은 편집기를 연 사용자로 고정한다. 계정이 바뀌면 RequireAuth 가 다시 마운트하고, 그 전에도 A 의 입력을 B 의 id 로 쓰지 않는다.
  const [userId, setUserId] = useState(user?.id);
  if (userId === undefined && user) setUserId(user.id);
  const draftKey = post ? draftKeys.editPost(post.id) : draftKeys.newPost;

  const [restored, setRestored] = useState<PostDraft | null>(() => (user ? readPostDraft(draftKey, user.id) : null));
  // 수정 초안의 바탕 글 시각. 서버 글이 더 새로우면 사용자가 고를 때까지 초안이 기억한 시각을 그대로 둔다(다시 열어도 경고가 남게).
  const [conflict, setConflict] = useState(() => !!post && !!restored && serverIsNewer(post, restored));
  const [baseUpdatedAt, setBaseUpdatedAt] = useState(() => (restored && post ? restored.baseUpdatedAt : post?.updatedAt));
  const start = restored ?? initialDraft(post);
  const [title, setTitle] = useState(start.title);
  const [content, setContent] = useState(start.content);
  const [categorySlug, setCategorySlug] = useState(start.categorySlug);
  const [tags, setTags] = useState<string[]>(start.tags);
  const [tagText, setTagText] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [items, setItems] = useState<UploadItem[]>(() => doneItems(start.attachments));
  // 진행·대기 중인 업로드의 취소 손잡이(TASK-31#5). '업로드 취소'와 언마운트가 abort 한다.
  const uploadControllers = useRef(new Map<string, AbortController>());
  useEffect(() => {
    const controllers = uploadControllers.current;
    return () => {
      for (const controller of controllers.values()) controller.abort();
      controllers.clear();
    };
  }, []);
  // 저장 때 연결이 거절된 첨부 id → 안내(TASK-31#4).
  const [problems, setProblems] = useState<Record<number, string>>({});
  const nextKey = useRef(0);
  // 한 번의 저장 의도 = 요청 하나(TASK-31#2 B1). 상태(isPending)는 다시 그려진 뒤에야 버튼을 막으므로, 같은 틱의 연속 제출(Enter 연타)은
  // ref 로 막는다. 실패하면 풀고, 성공하면 이동할 때까지 잠근 채 둔다.
  const submitLock = useRef(false);

  // 초안 저장(TASK-29#3). 원래 값(작성: 빈 폼, 수정: 받은 글)과 같으면 지운다.
  const doneAttachments = items.flatMap((item) => (item.status === "done" ? [item.attachment] : []));
  const draft: PostDraft = {
    title,
    content,
    categorySlug,
    tags,
    attachments: doneAttachments,
    ...(post && baseUpdatedAt ? { baseUpdatedAt } : {}),
  };
  const pristine = sameDraft(draft, initialDraft(post));
  const uploading = items.some((item) => item.status === "uploading");
  const autosave = useDraftAutosave({ key: draftKey, ownerId: userId, data: draft, empty: pristine });

  const discardDraft = () => {
    const initial = initialDraft(post);
    setTitle(initial.title);
    setContent(initial.content);
    setCategorySlug(initial.categorySlug);
    setTags(initial.tags);
    setTagText("");
    setItems(doneItems(initial.attachments));
    setFieldErrors({});
    setRestored(null);
    setConflict(false);
    setBaseUpdatedAt(post?.updatedAt);
    autosave.discard();
  };

  /** 서버 글이 바뀌었어도 초안을 쓴다: 이제 지금 서버 글을 바탕으로 본다(다음에 열 때 다시 경고하지 않는다). */
  const keepDraft = () => {
    setConflict(false);
    setBaseUpdatedAt(post?.updatedAt);
  };

  const save = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      post
        ? api<PostDetail>(`/posts/${post.id}`, { method: "PATCH", body })
        : api<PostDetail>("/posts", { method: "POST", body }),
    onSuccess: (saved) => {
      autosave.discard();
      // FE-19(TASK-31#1): 작성 응답(조회수 0, 이번 조회 전)으로 상세 캐시를 채우지 않는다 — 상세 화면이 GET 으로 받아 조회수가 맞다.
      // 수정은 바뀐 내용을 바로 보이게 응답으로 채우되 stale 로 표시해 상세가 열리면 다시 받는다.
      if (post) {
        client.setQueryData(queryKeys.post(saved.id), saved);
        void client.invalidateQueries({ queryKey: queryKeys.post(saved.id), refetchType: "none" });
      }
      void client.invalidateQueries({ queryKey: queryKeys.postLists });
      void client.invalidateQueries({ queryKey: ["tags"] });
      // PRG(TASK-31#2): 결과 페이지로 replace — 편집기가 히스토리에서 빠져 뒤로가기·새로고침으로 다시 제출되지 않는다.
      // 잠금(submitLock)은 풀지 않고 버튼도 비활성으로 둔다(이동이 끝나기 전 재클릭으로 두 번째 글이 생기지 않게, B1).
      router.replace(`/posts/view/?id=${saved.id}`);
    },
    onError: (error, body) => {
      submitLock.current = false;
      if (!isApiError(error)) return;
      const sentIds = Array.isArray(body.attachmentIds) ? (body.attachmentIds as number[]) : [];
      // 그사이 삭제됐거나(404) 권한이 바뀐(403) 글: 상세 캐시를 다시 받게 한다(FE-21).
      if (post && (error.status === 404 || error.status === 403)) {
        void client.invalidateQueries({ queryKey: queryKeys.post(post.id) });
      }
      if (error.fields) {
        const { mapped, attachmentIndexes } = mapFieldErrors(error.fields);
        setFieldErrors(mapped);
        const found: Record<number, string> = {};
        for (const [index, message] of attachmentIndexes) {
          if (sentIds[index] !== undefined) found[sentIds[index]] = message;
        }
        setProblems(found);
      } else if (ATTACHMENT_LINK_ERRORS.includes(error.code)) {
        const id = linkErrorAttachmentId(error.message, sentIds);
        if (id !== null) setProblems({ [id]: errorMessage(error) });
        else setFieldErrors({ attachments: errorMessage(error) });
      } else if (error.code === "invalid_tag" || error.code === "too_many_tags") setFieldErrors({ tags: errorMessage(error) });
      else if (error.code === "unknown_category" || error.code === "category_admin_only")
        setFieldErrors({ category: errorMessage(error) });
    },
  });
  // 칸에 붙인 오류는 폼 위 알림으로 한 번 더 보이지 않는다. 폼에 없는 필드 키만 남았으면 그 메시지를 폼 위에 보인다(TASK-31#3).
  const unmappedFields =
    save.isError && isApiError(save.error) && save.error.fields ? mapFieldErrors(save.error.fields).unmapped : [];
  const formError =
    save.isError &&
    !(
      isApiError(save.error) &&
      (save.error.fields ||
        ["invalid_tag", "too_many_tags", "unknown_category", "category_admin_only", ...ATTACHMENT_LINK_ERRORS].includes(
          save.error.code,
        ))
    )
      ? save.error
      : null;

  // 이탈 경고(TASK-31#7, B2: 저장하지 않은 입력이 경고 없이 버려지지 않는다). 저장에 성공해 이동하는 중에는 묻지 않는다.
  // - 탭 닫기·새로고침: 입력이 있거나 업로드·저장 중이면 경고(탭을 닫으면 sessionStorage 초안도 사라진다).
  // - 앱 안 이동·취소: 글자 입력은 초안(TASK-29)에 남아 다시 열면 복원되므로 묻지 않는다. 업로드 중(떠나면 끊김)·저장 중(결과를 못 봄)·
  //   초안 저장 실패(저장 공간)일 때만 묻는다.
  const leaving = useLeaveGuard({
    unload: !save.isSuccess && (!pristine || uploading || save.isPending),
    inAppMessage: () => {
      if (save.isSuccess) return null;
      if (uploading) return "이미지를 올리는 중입니다. 이 페이지를 떠나면 업로드가 취소됩니다. 떠날까요?";
      if (save.isPending) return "저장하는 중입니다. 지금 떠나면 저장됐는지 확인하지 못할 수 있습니다. 떠날까요?";
      if (!pristine && autosave.saveFailed()) {
        return "입력한 내용을 임시 저장하지 못했습니다. 이 페이지를 떠나면 입력이 사라집니다. 떠날까요?";
      }
      return null;
    },
  });

  /** 취소: 확인을 거쳐 뒤로. 새 탭으로 바로 열어 돌아갈 곳이 없으면 목록(/)으로(TASK-31#7, FE-28). */
  const cancel = () => {
    if (!leaving.confirmLeave()) return;
    if (window.history.length > 1) router.back();
    else router.replace("/");
  };

  const listedCategories = (categories.data ?? []).filter(
    (category) => !category.adminOnly || isAdmin || category.slug === post?.category?.slug,
  );
  // 지금 값(글의 카테고리·복원한 초안)이 목록에 없어도(목록 실패·로딩 중·그사이 삭제) 선택지로 둔다 — 없으면 select 가 "카테고리 없음"을
  // 보이면서 실제로는 그 값을 보내 표시와 전송값이 어긋났다(TASK-31#8, FE-30). 없는 카테고리면 저장 때 서버가 unknown_category 로 알린다.
  const visibleCategories =
    categorySlug && !listedCategories.some((category) => category.slug === categorySlug)
      ? [
          ...listedCategories,
          { slug: categorySlug, name: post?.category?.slug === categorySlug ? post.category.name : categorySlug },
        ]
      : listedCategories;
  // confirm 만 남은 실패(TASK-31#6)도 자리를 차지한다(다시 시도하면 붙는다).
  const attached = items.filter((item) => item.status !== "error" || item.confirmId !== undefined).length;

  // --- 태그 ---
  const commitTag = (raw: string) => {
    if (!raw.trim()) return;
    const result = addTag(tags, raw);
    setTags(result.tags);
    setFieldErrors((errors) => ({ ...errors, tags: result.error }));
    if (!result.error) setTagText("");
  };

  const onTagChange = (event: ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    if (!value.includes(",")) {
      setTagText(value);
      return;
    }
    // 쉼표는 구분자: 앞부분을 차례로 붙이고 마지막 조각은 입력에 남긴다.
    const parts = value.split(",");
    let current = tags;
    let error: string | undefined;
    for (const part of parts.slice(0, -1)) {
      if (!part.trim()) continue;
      const result = addTag(current, part);
      current = result.tags;
      error = result.error ?? error;
    }
    setTags(current);
    setFieldErrors((errors) => ({ ...errors, tags: error }));
    setTagText(parts.at(-1) ?? "");
  };

  const onTagKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" && !event.nativeEvent.isComposing) {
      event.preventDefault();
      commitTag(tagText);
    }
  };

  const preview = tagText.trim() ? normalizeTag(tagText) : null;

  // --- 이미지 ---
  const updateItem = (key: string, next: UploadItem) =>
    setItems((current) => current.map((item) => (item.key === key ? next : item)));

  const onFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    let room = MAX_ATTACHMENTS - attached;
    const queue: { key: string; file: File }[] = [];
    const added: UploadItem[] = [];
    for (const file of files) {
      const key = `upload-${nextKey.current++}`;
      const invalid = room <= 0 ? `첨부는 ${MAX_ATTACHMENTS}개까지입니다.` : validateImageFile(file);
      if (invalid) {
        added.push({ key, name: file.name, status: "error", error: invalid });
        continue;
      }
      room--;
      added.push({ key, name: file.name, status: "uploading", progress: 0 });
      queue.push({ key, file });
    }
    setItems((current) => [...current, ...added]);

    for (const { key } of queue) uploadControllers.current.set(key, new AbortController());

    // 하나씩 올린다(진행률을 파일별로 보이고 presign URL 5분 만료 안에 끝내려고). 취소된 것(대기 중 취소·언마운트)은 건너뛴다.
    for (const { key, file } of queue) {
      const controller = uploadControllers.current.get(key);
      if (!controller || controller.signal.aborted) continue;
      try {
        const attachment = await uploadImage(file, {
          signal: controller.signal,
          onProgress: (progress) => updateItem(key, { key, name: file.name, status: "uploading", progress }),
        });
        updateItem(key, { key, name: file.name, status: "done", attachment });
      } catch (error) {
        // 취소는 항목을 이미 뺐다.
        if (!controller.signal.aborted) {
          updateItem(key, {
            key,
            name: file.name,
            status: "error",
            error: errorMessage(error),
            confirmId: isRetryableConfirmError(error) ? error.attachmentId : undefined,
          });
        }
      } finally {
        uploadControllers.current.delete(key);
      }
    }
  };

  /** confirm 만 다시(TASK-31#6). 다시 올리지 않는다 — 같은 URL 재PUT 은 412 고, 새로 올리면 지금 객체가 고아가 된다. */
  const retryConfirm = async (key: string, name: string, attachmentId: number) => {
    const controller = new AbortController();
    uploadControllers.current.set(key, controller);
    updateItem(key, { key, name, status: "uploading", progress: 100 });
    try {
      const attachment = await confirmAttachment(attachmentId, controller.signal);
      updateItem(key, { key, name, status: "done", attachment });
    } catch (error) {
      if (!controller.signal.aborted) {
        const confirmId = isRetryableConfirmFailure(error) ? attachmentId : undefined;
        updateItem(key, { key, name, status: "error", error: errorMessage(error), confirmId });
      }
    } finally {
      uploadControllers.current.delete(key);
    }
  };

  /** 항목 빼기. 올리는 중이거나 차례를 기다리는 것이면 업로드도 끊는다(TASK-31#5). */
  const removeItem = (key: string) => {
    uploadControllers.current.get(key)?.abort();
    uploadControllers.current.delete(key);
    setItems((current) => current.filter((item) => item.key !== key));
  };
  const problemOf = (item: UploadItem) => (item.status === "done" ? problems[item.attachment.id] : undefined);
  const hasProblems = items.some((item) => problemOf(item));

  // --- 제출 ---
  const submit = (event: FormEvent) => {
    event.preventDefault();
    submitWith(items);
  };

  /** 문제 첨부만 빼고 다시 저장(TASK-31#4). 칸에서 빼고, 같은 목록으로 바로 보낸다(상태 반영을 기다리지 않는다). */
  const saveWithoutProblems = () => {
    const remaining = items.filter((item) => !problemOf(item));
    setItems(remaining);
    submitWith(remaining);
  };

  const submitWith = (current: UploadItem[]) => {
    const errors: FieldErrors = validatePost({ title, content });
    let finalTags = tags;
    if (tagText.trim()) {
      const result = addTag(tags, tagText);
      if (result.error) errors.tags = result.error;
      else {
        finalTags = result.tags;
        setTags(finalTags);
        setTagText("");
      }
    }
    setFieldErrors(errors);
    if (submitLock.current) return;
    if (Object.values(errors).some(Boolean) || uploading) return;

    setProblems({});
    const attachmentIds = current.flatMap((item) => (item.status === "done" ? [item.attachment.id] : []));
    const body: Record<string, unknown> = { title, content };
    // 작성: 카테고리 없음이면 생략. 수정: "" 는 카테고리를 뺀다(PATCH 규약).
    if (post) body.categorySlug = categorySlug;
    else if (categorySlug) body.categorySlug = categorySlug;
    body.tags = finalTags;
    body.attachmentIds = attachmentIds;
    // 요청 중 세션이 끝나도 마지막 입력이 초안에 있게 기다리지 않고 저장한다.
    autosave.flush();
    submitLock.current = true;
    save.mutate(body);
  };

  return (
    <form onSubmit={submit} noValidate className="flex w-full flex-col gap-5" data-testid="post-editor">
      <h1 className="text-2xl font-semibold tracking-tight">{post ? "글 수정" : "글쓰기"}</h1>

      {restored && conflict && (
        <Alert variant="destructive" data-testid="draft-conflict">
          <AlertDescription className="flex flex-col gap-2">
            <p>
              이 글은 그사이 수정되었습니다. 불러온 초안은 이전 글을 바탕으로 쓴 것이라, 그대로 저장하면 그사이 바뀐 내용을 덮어씁니다.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="outline" size="xs" onClick={keepDraft}>
                초안 유지
              </Button>
              <Button type="button" variant="outline" size="xs" onClick={discardDraft}>
                초안 버리고 최신 글 불러오기
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}
      {restored && !conflict && (
        <Alert data-testid="draft-restored">
          <AlertDescription className="flex flex-wrap items-center gap-2">
            <span>임시 저장된 초안을 불러왔습니다.</span>
            <Button type="button" variant="outline" size="xs" onClick={discardDraft}>
              초안 버리기
            </Button>
          </AlertDescription>
        </Alert>
      )}

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${ids}-title`}>제목</Label>
        <Input
          id={`${ids}-title`}
          data-testid="editor-title"
          value={title}
          maxLength={TITLE_MAX}
          onChange={(e) => setTitle(e.target.value)}
          aria-invalid={fieldErrors.title ? true : undefined}
        />
        <FieldError message={fieldErrors.title} testId="editor-title-error" />
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${ids}-category`}>카테고리</Label>
        <select
          id={`${ids}-category`}
          data-testid="editor-category"
          className={SELECT}
          value={categorySlug}
          onChange={(e) => setCategorySlug(e.target.value)}
        >
          <option value="">카테고리 없음</option>
          {visibleCategories.map((category) => (
            <option key={category.slug} value={category.slug}>
              {category.name}
            </option>
          ))}
        </select>
        <FieldError message={fieldErrors.category} testId="editor-category-error" />
        {categories.isError && (
          <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="editor-categories-error">
            <span className="text-destructive">카테고리 목록을 불러오지 못했습니다. {errorMessage(categories.error)}</span>
            <Button
              type="button"
              variant="outline"
              size="xs"
              disabled={categories.isFetching}
              onClick={() => void categories.refetch()}
            >
              다시 시도
            </Button>
          </div>
        )}
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${ids}-content`}>본문</Label>
        <textarea
          id={`${ids}-content`}
          data-testid="editor-content"
          className={TEXTAREA}
          value={content}
          maxLength={CONTENT_MAX}
          onChange={(e) => setContent(e.target.value)}
          aria-invalid={fieldErrors.content ? true : undefined}
        />
        <FieldError message={fieldErrors.content} testId="editor-content-error" />
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${ids}-tag`}>태그 (최대 {MAX_TAGS}개, Enter 또는 쉼표로 추가)</Label>
        <div className="flex flex-wrap items-center gap-1" data-testid="editor-tags">
          {tags.map((tag) => (
            <Badge key={tag} variant="secondary">
              #{tag}
              <button
                type="button"
                aria-label={`태그 ${tag} 빼기`}
                onClick={() => setTags(tags.filter((t) => t !== tag))}
                className="ml-0.5"
              >
                <X className="size-3" aria-hidden />
              </button>
            </Badge>
          ))}
        </div>
        <div className="flex gap-2">
          <Input
            id={`${ids}-tag`}
            data-testid="editor-tag-input"
            value={tagText}
            onChange={onTagChange}
            onKeyDown={onTagKeyDown}
            className="sm:w-64"
          />
          <Button type="button" variant="outline" onClick={() => commitTag(tagText)}>
            태그 추가
          </Button>
        </div>
        {preview && (
          <p className="text-muted-foreground text-xs" data-testid="editor-tag-preview">
            {preview.error ? preview.error : <>저장될 이름: #{preview.name}</>}
          </p>
        )}
        {/* 입력 중인 값의 오류는 미리보기 줄이 이미 보여 준다. */}
        {!preview?.error && <FieldError message={fieldErrors.tags} testId="editor-tags-error" />}
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${ids}-files`}>이미지 (PNG·JPEG·WebP·GIF, 5MB 이하, 최대 {MAX_ATTACHMENTS}개)</Label>
        <input
          id={`${ids}-files`}
          data-testid="editor-file-input"
          type="file"
          multiple
          accept={ALLOWED_IMAGE_TYPES.join(",")}
          disabled={attached >= MAX_ATTACHMENTS}
          onChange={onFiles}
          className="text-sm"
        />
        <ul className="grid gap-2 sm:grid-cols-3" data-testid="editor-attachments">
          {items.map((item, index) => (
            <li key={item.key} className="flex flex-col gap-1 rounded-lg border p-2 text-xs">
              {item.status === "done" && (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={item.attachment.url} alt={`첨부 이미지 ${index + 1}`} className="h-28 w-full object-cover" />
              )}
              {item.status === "uploading" && (
                <progress
                  max={100}
                  value={item.progress}
                  aria-label={`${item.name} 업로드 진행률`}
                  data-testid="editor-upload-progress"
                  className="w-full"
                />
              )}
              {problemOf(item) && (
                <p className="text-destructive" role="alert" data-testid="editor-attachment-problem">
                  첨부 이미지 {index + 1}: {problemOf(item)}
                </p>
              )}
              {item.status === "error" && (
                <p className="text-destructive" role="alert">
                  {item.name}: {item.error}
                </p>
              )}
              {item.status === "error" && item.confirmId !== undefined && (
                <Button
                  type="button"
                  variant="outline"
                  size="xs"
                  onClick={() => void retryConfirm(item.key, item.name, item.confirmId!)}
                >
                  첨부 이미지 {index + 1} 확인 다시 시도
                </Button>
              )}
              <Button type="button" variant="ghost" size="xs" onClick={() => removeItem(item.key)}>
                첨부 이미지 {index + 1} {item.status === "uploading" ? "업로드 취소" : "빼기"}
              </Button>
            </li>
          ))}
        </ul>
        <FieldError message={fieldErrors.attachments} testId="editor-attachments-error" />
        {hasProblems && (
          <div>
            <Button type="button" variant="outline" size="sm" disabled={save.isPending} onClick={saveWithoutProblems}>
              문제 이미지 빼고 다시 저장
            </Button>
          </div>
        )}
      </div>

      {formError && <ApiErrorAlert error={formError} testId="editor-error" />}
      {unmappedFields.length > 0 && (
        <Alert variant="destructive" data-testid="editor-error">
          <AlertDescription>
            <p>{errorMessage(save.error)}</p>
            <ul className="list-disc pl-5">
              {unmappedFields.map((message, index) => (
                <li key={index}>{message}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      <div className="flex gap-2">
        <Button type="submit" data-testid="editor-submit" disabled={save.isPending || save.isSuccess || uploading}>
          {post ? "저장" : "등록"}
        </Button>
        <Button type="button" variant="outline" onClick={cancel}>
          취소
        </Button>
      </div>
    </form>
  );
}

function FieldError({ message, testId }: { message?: string; testId?: string }) {
  if (!message) return null;
  return (
    <p className="text-destructive text-sm" data-testid={testId}>
      {message}
    </p>
  );
}

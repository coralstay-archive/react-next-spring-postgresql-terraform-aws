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
import { api, isApiError } from "@/lib/api";
import { useSessionUser } from "@/lib/auth";
import { useCategories, type Attachment, type PostDetail } from "@/lib/board";
import { CONTENT_MAX, TITLE_MAX, validatePost, type PostInput } from "@/lib/board-validation";
import { clearDraft, draftKeys, loadDraft, saveDraft } from "@/lib/drafts";
import { errorMessage } from "@/lib/errors";
import { queryKeys } from "@/lib/query";
import { addTag, MAX_TAGS, normalizeTag } from "@/lib/tags";
import { ALLOWED_IMAGE_TYPES, MAX_ATTACHMENTS, uploadImage, validateImageFile } from "@/lib/uploads";

type UploadItem =
  | { key: string; name: string; status: "uploading"; progress: number }
  | { key: string; name: string; status: "done"; attachment: Attachment }
  | { key: string; name: string; status: "error"; error: string };

/** 글 초안(TASK-29#3). 업로드가 끝난 첨부만 담는다(진행 중·실패한 것은 다시 올려야 한다). */
interface PostDraft {
  title: string;
  content: string;
  categorySlug: string;
  tags: string[];
  attachments: Attachment[];
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
  };
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

type FieldErrors = Partial<Record<keyof PostInput | "category" | "tags", string>>;

const TEXTAREA =
  "border-input min-h-64 w-full rounded-lg border bg-transparent px-2.5 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive";
const SELECT = "border-input h-8 w-full rounded-lg border bg-transparent px-2 text-sm sm:w-64";

/**
 * 글 작성·수정 폼 (TASK-14#3). `post` 가 있으면 수정(PATCH, 보낸 필드만 바뀐다)이다.
 * - 카테고리: `adminOnly` 는 ADMIN 에게만 보인다(서버도 403 `category_admin_only`).
 * - 태그: 서버와 같은 정규화를 미리 보여 주고 최대 5개.
 * - 이미지: 선검증 → presign → PUT(진행률) → confirm 한 첨부의 id 를 `attachmentIds` 로 보낸다. 업로드 중에는 등록을 막는다.
 * - 초안(TASK-29#3, B1): 입력이 바뀔 때마다 sessionStorage 에 저장하고(원래 값과 같으면 지움), 다시 열면 복원한다. 세션이 끝나도
 *   편집기는 언마운트되지 않는다(RequireAuth). 저장에 성공하면 초안을 지운다.
 */
export function PostEditor({ post }: { post?: PostDetail }) {
  const ids = useId();
  const router = useRouter();
  const client = useQueryClient();
  // 세션이 끝나도 마지막 사용자로 판단한다(카테고리 노출·초안 주인).
  const { user } = useSessionUser();
  const categories = useCategories();
  const isAdmin = user?.role === "ADMIN";
  const userId = user?.id;
  const draftKey = post ? draftKeys.editPost(post.id) : draftKeys.newPost;

  const [restored, setRestored] = useState<PostDraft | null>(() => (user ? readPostDraft(draftKey, user.id) : null));
  const start = restored ?? initialDraft(post);
  const [title, setTitle] = useState(start.title);
  const [content, setContent] = useState(start.content);
  const [categorySlug, setCategorySlug] = useState(start.categorySlug);
  const [tags, setTags] = useState<string[]>(start.tags);
  const [tagText, setTagText] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [items, setItems] = useState<UploadItem[]>(() => doneItems(start.attachments));
  const nextKey = useRef(0);

  // 초안 저장(TASK-29#3). 원래 값(작성: 빈 폼, 수정: 받은 글)과 같으면 지운다.
  const doneAttachments = items.flatMap((item) => (item.status === "done" ? [item.attachment] : []));
  const draft: PostDraft = { title, content, categorySlug, tags, attachments: doneAttachments };
  const draftJson = JSON.stringify(draft);
  const pristine = sameDraft(draft, initialDraft(post));
  useEffect(() => {
    if (userId === undefined) return;
    if (pristine) clearDraft(draftKey, userId);
    else saveDraft(draftKey, userId, JSON.parse(draftJson));
  }, [draftKey, userId, pristine, draftJson]);

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
    clearDraft(draftKey, userId);
  };

  const save = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      post
        ? api<PostDetail>(`/posts/${post.id}`, { method: "PATCH", body })
        : api<PostDetail>("/posts", { method: "POST", body }),
    onSuccess: (saved) => {
      clearDraft(draftKey);
      client.setQueryData(queryKeys.post(saved.id), saved);
      void client.invalidateQueries({ queryKey: queryKeys.postLists });
      void client.invalidateQueries({ queryKey: ["tags"] });
      router.push(`/posts/view/?id=${saved.id}`);
    },
    onError: (error) => {
      if (!isApiError(error)) return;
      if (error.fields) setFieldErrors(error.fields as FieldErrors);
      else if (error.code === "invalid_tag" || error.code === "too_many_tags") setFieldErrors({ tags: errorMessage(error) });
      else if (error.code === "unknown_category" || error.code === "category_admin_only")
        setFieldErrors({ category: errorMessage(error) });
    },
  });
  // 칸에 붙인 오류는 폼 위 알림으로 한 번 더 보이지 않는다.
  const formError =
    save.isError &&
    !(
      isApiError(save.error) &&
      (save.error.fields ||
        ["invalid_tag", "too_many_tags", "unknown_category", "category_admin_only"].includes(save.error.code))
    )
      ? save.error
      : null;

  const visibleCategories = (categories.data ?? []).filter(
    (category) => !category.adminOnly || isAdmin || category.slug === post?.category?.slug,
  );
  const uploading = items.some((item) => item.status === "uploading");
  const attached = items.filter((item) => item.status !== "error").length;

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

    // 하나씩 올린다(진행률을 파일별로 보이고 presign URL 5분 만료 안에 끝내려고).
    for (const { key, file } of queue) {
      try {
        const attachment = await uploadImage(file, (progress) =>
          updateItem(key, { key, name: file.name, status: "uploading", progress }),
        );
        updateItem(key, { key, name: file.name, status: "done", attachment });
      } catch (error) {
        updateItem(key, { key, name: file.name, status: "error", error: errorMessage(error) });
      }
    }
  };

  const removeItem = (key: string) => setItems((current) => current.filter((item) => item.key !== key));

  // --- 제출 ---
  const submit = (event: FormEvent) => {
    event.preventDefault();
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
    if (Object.values(errors).some(Boolean) || uploading) return;

    const attachmentIds = items.flatMap((item) => (item.status === "done" ? [item.attachment.id] : []));
    const body: Record<string, unknown> = { title, content };
    // 작성: 카테고리 없음이면 생략. 수정: "" 는 카테고리를 뺀다(PATCH 규약).
    if (post) body.categorySlug = categorySlug;
    else if (categorySlug) body.categorySlug = categorySlug;
    body.tags = finalTags;
    body.attachmentIds = attachmentIds;
    save.mutate(body);
  };

  return (
    <form onSubmit={submit} noValidate className="flex w-full flex-col gap-5" data-testid="post-editor">
      <h1 className="text-2xl font-semibold tracking-tight">{post ? "글 수정" : "글쓰기"}</h1>

      {restored && (
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
        <FieldError message={fieldErrors.title} />
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
        <FieldError message={fieldErrors.category} />
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
        <FieldError message={fieldErrors.content} />
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
        {!preview?.error && <FieldError message={fieldErrors.tags} />}
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
              {item.status === "error" && (
                <p className="text-destructive" role="alert">
                  {item.name}: {item.error}
                </p>
              )}
              {item.status !== "uploading" && (
                <Button type="button" variant="ghost" size="xs" onClick={() => removeItem(item.key)}>
                  첨부 이미지 {index + 1} 빼기
                </Button>
              )}
            </li>
          ))}
        </ul>
      </div>

      {formError && <ApiErrorAlert error={formError} testId="editor-error" />}

      <div className="flex gap-2">
        <Button type="submit" data-testid="editor-submit" disabled={save.isPending || uploading}>
          {post ? "저장" : "등록"}
        </Button>
        <Button type="button" variant="outline" onClick={() => router.back()}>
          취소
        </Button>
      </div>
    </form>
  );
}

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return <p className="text-destructive text-sm">{message}</p>;
}

"use client";

import { useId, useState, type FormEvent } from "react";

import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { ApiErrorAlert, RefetchErrorAlert } from "@/components/board/api-error-alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  createPayload,
  updatePayload,
  useDeleteCategory,
  useSaveCategory,
  validateCategoryForm,
  type CategoryForm,
  type CategoryFormErrors,
} from "@/lib/admin";
import { isApiError } from "@/lib/api";
import { useCategories, type Category } from "@/lib/board";

const EMPTY: CategoryForm = { slug: "", name: "", sortOrder: "", adminOnly: false };

function toForm(category: Category): CategoryForm {
  return {
    slug: category.slug,
    name: category.name,
    sortOrder: String(category.sortOrder),
    adminOnly: category.adminOnly,
  };
}

/**
 * 카테고리 탭 (TASK-15#1). 목록은 공개 `/api/categories`(관리 전용 목록 API 는 없다)를 그대로 쓰고, 생성·수정·삭제 뒤
 * `["categories"]` 를 무효화해 게시판 탭·편집기도 새 목록을 받게 한다.
 */
export function CategoriesTab() {
  const categories = useCategories();
  const [editing, setEditing] = useState<Category | null>(null);
  const [formKey, setFormKey] = useState(0);
  const [deleting, setDeleting] = useState<Category | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [dialogKey, setDialogKey] = useState(0);
  const remove = useDeleteCategory();

  const startEdit = (category: Category | null) => {
    setEditing(category);
    setFormKey((k) => k + 1);
  };

  const askDelete = (category: Category) => {
    remove.reset();
    setDialogKey((k) => k + 1);
    setDeleting(category);
    setDeleteOpen(true);
  };

  const confirmDelete = () => {
    if (!deleting) return;
    const target = deleting;
    remove.mutate(target.id, {
      onSuccess: () => {
        setDeleteOpen(false);
        if (editing?.id === target.id) startEdit(null);
      },
    });
  };

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
      <div className="flex flex-col gap-3">
        {categories.isError && categories.data && (
          <RefetchErrorAlert
            error={categories.error}
            retrying={categories.isFetching}
            onRetry={() => void categories.refetch()}
            testId="admin-categories-refetch-error"
          />
        )}
        {categories.isError && !categories.data ? (
          <ApiErrorAlert error={categories.error} testId="admin-categories-error" />
        ) : categories.isPending ? (
          <p role="status" className="text-muted-foreground text-sm">
            불러오는 중…
          </p>
        ) : categories.data.length === 0 ? (
          <p className="text-muted-foreground py-8 text-center text-sm" data-testid="admin-categories-empty">
            카테고리가 없습니다.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-sm" data-testid="admin-category-table">
              <thead className="bg-muted/50 text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">순서</th>
                  <th className="px-3 py-2 font-medium">slug</th>
                  <th className="px-3 py-2 font-medium">이름</th>
                  <th className="px-3 py-2 font-medium">
                    <span className="sr-only">작업</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {categories.data.map((category) => (
                  <tr key={category.id} data-testid="admin-category-row" data-category-id={category.id}>
                    <td className="px-3 py-2 tabular-nums">{category.sortOrder}</td>
                    <td className="px-3 py-2 font-mono">{category.slug}</td>
                    <td className="px-3 py-2">
                      <span>{category.name}</span>
                      {category.adminOnly && (
                        <Badge variant="secondary" className="ml-1" data-testid="admin-category-admin-only-badge">
                          관리자 전용
                        </Badge>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right whitespace-nowrap">
                      <Button
                        size="sm"
                        variant="outline"
                        className="mr-2"
                        data-testid="admin-category-edit"
                        onClick={() => startEdit(category)}
                      >
                        수정
                      </Button>
                      <Button
                        size="sm"
                        variant="destructive"
                        data-testid="admin-category-delete"
                        onClick={() => askDelete(category)}
                      >
                        삭제
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <CategoryEditor key={`editor-${formKey}`} editing={editing} onDone={() => startEdit(null)} />

      <ConfirmDialog
        key={`dialog-${dialogKey}`}
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title="카테고리를 삭제할까요?"
        description={`"${deleting?.name ?? ""}"(${deleting?.slug ?? ""})를 삭제합니다. 이 카테고리의 글은 남고 카테고리 없음이 됩니다.`}
        confirmLabel="삭제"
        destructive
        pending={remove.isPending}
        error={remove.error}
        onConfirm={confirmDelete}
      />
    </div>
  );
}

/** 생성·수정 폼. 서버 규칙(slug `^[a-z0-9-]{2,30}$`, 이름 1~50, 순서 0~10000 정수)을 먼저 확인한다. 수정은 바뀐 필드만 PATCH. */
function CategoryEditor({ editing, onDone }: { editing: Category | null; onDone: () => void }) {
  const [form, setForm] = useState<CategoryForm>(editing ? toForm(editing) : EMPTY);
  const [errors, setErrors] = useState<CategoryFormErrors>({});
  const save = useSaveCategory();
  const id = useId();

  const set = <K extends keyof CategoryForm>(key: K, value: CategoryForm[K]) => setForm((f) => ({ ...f, [key]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateCategoryForm(form, editing ?? undefined);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const payload = editing ? updatePayload(editing, form) : createPayload(form);
    if (editing && Object.keys(payload).length === 0) {
      onDone();
      return;
    }
    save.mutate(
      { id: editing?.id, payload },
      {
        onSuccess: () => {
          setForm(EMPTY);
          onDone();
        },
        onError: (error) => {
          if (isApiError(error) && error.fields) setErrors(error.fields as CategoryFormErrors);
        },
      },
    );
  };

  const fieldError = (key: keyof CategoryForm, testId: string) =>
    errors[key] ? (
      <p className="text-destructive text-sm" data-testid={testId} id={`${id}-${key}-error`}>
        {errors[key]}
      </p>
    ) : null;

  return (
    <Card size="sm" className="h-fit">
      <CardHeader>
        <CardTitle>{editing ? `카테고리 수정 (#${editing.id})` : "새 카테고리"}</CardTitle>
      </CardHeader>
      <CardContent>
        <form noValidate onSubmit={submit} className="flex flex-col gap-3" data-testid="admin-category-form">
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-slug`}>slug</Label>
            <Input
              id={`${id}-slug`}
              data-testid="admin-category-slug"
              value={form.slug}
              onChange={(e) => set("slug", e.target.value)}
              aria-invalid={errors.slug ? true : undefined}
              aria-describedby={errors.slug ? `${id}-slug-error` : undefined}
              placeholder="free-talk"
              autoComplete="off"
            />
            {fieldError("slug", "admin-category-slug-error")}
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-name`}>이름</Label>
            <Input
              id={`${id}-name`}
              data-testid="admin-category-name"
              value={form.name}
              onChange={(e) => set("name", e.target.value)}
              aria-invalid={errors.name ? true : undefined}
              aria-describedby={errors.name ? `${id}-name-error` : undefined}
            />
            {fieldError("name", "admin-category-name-error")}
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-sortOrder`}>순서 {editing ? "" : "(비우면 맨 뒤)"}</Label>
            <Input
              id={`${id}-sortOrder`}
              data-testid="admin-category-sort-order"
              inputMode="numeric"
              value={form.sortOrder}
              onChange={(e) => set("sortOrder", e.target.value)}
              aria-invalid={errors.sortOrder ? true : undefined}
              aria-describedby={errors.sortOrder ? `${id}-sortOrder-error` : undefined}
            />
            {fieldError("sortOrder", "admin-category-sort-order-error")}
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              data-testid="admin-category-admin-only"
              checked={form.adminOnly}
              onChange={(e) => set("adminOnly", e.target.checked)}
            />
            관리자만 글쓰기(공지 등)
          </label>
          <ApiErrorAlert error={save.error} testId="admin-category-error" />
          <div className="flex gap-2">
            <Button type="submit" disabled={save.isPending} data-testid="admin-category-submit">
              {editing ? "저장" : "추가"}
            </Button>
            {editing && (
              <Button type="button" variant="outline" onClick={onDone} data-testid="admin-category-cancel">
                취소
              </Button>
            )}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

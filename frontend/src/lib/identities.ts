"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";
import type { Provider } from "@/lib/auth";
import { queryKeys } from "@/lib/query";

/** `GET /api/me/identities` 한 줄. */
export interface Identity {
  id: number;
  provider: Provider;
  email: string | null;
  linkedAt: string;
}

export function useIdentities() {
  return useQuery({ queryKey: queryKeys.identities, queryFn: () => api<Identity[]>("/me/identities") });
}

/** 연결·해제 뒤: 목록과 me(providers) 를 다시 받는다. */
export function useRefreshAccount() {
  const client = useQueryClient();
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: queryKeys.identities }),
      client.invalidateQueries({ queryKey: queryKeys.me }),
    ]);
}

/** `DELETE /api/me/identities/{id}`. 마지막 수단은 409 last_identity. */
export function useUnlinkIdentity() {
  const refresh = useRefreshAccount();
  return useMutation({
    mutationFn: (identity: Identity) => api<void>(`/me/identities/${identity.id}`, { method: "DELETE" }),
    onSuccess: () => refresh(),
  });
}

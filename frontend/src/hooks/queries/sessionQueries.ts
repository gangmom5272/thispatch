import { queryOptions, useQuery, useQueryClient } from "@tanstack/react-query"
import { getSession } from "@/api/session"
import { clearTokens } from "@/lib/tokenStorage"

export const sessionKeys = {
  all: ["session"] as const,
  current: () => [...sessionKeys.all, "current"] as const,
}

export const sessionOptions = () =>
  queryOptions({
    queryKey: sessionKeys.current(),
    queryFn: ({ signal }) => getSession(signal),
  })

export function useSession() {
  return useQuery(sessionOptions())
}

export function useLogout() {
  const queryClient = useQueryClient()

  return () => {
    clearTokens()
    void queryClient.resetQueries({ queryKey: sessionKeys.all })
  }
}

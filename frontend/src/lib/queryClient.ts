import { QueryClient } from "@tanstack/react-query"
import { isApiError } from "../api/error"

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      retry: (failureCount, error) =>
        failureCount < 2 && (!isApiError(error) || error.status === 0 || error.status >= 500),
      refetchOnWindowFocus: false,
    },
  },
})

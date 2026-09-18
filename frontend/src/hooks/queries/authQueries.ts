import { mutationOptions, useMutation, useQueryClient } from "@tanstack/react-query"
import { login, signup, type LoginRequest, type SignupRequest } from "@/api/auth"
import { setTokens } from "@/lib/tokenStorage"
import { sessionKeys } from "@/hooks/queries/sessionQueries"

export const loginMutationOptions = mutationOptions({
  mutationFn: (body: LoginRequest) => login(body),
})

export const signupMutationOptions = mutationOptions({
  mutationFn: (body: SignupRequest) => signup(body),
})

export function useLogin() {
  const queryClient = useQueryClient()

  return useMutation({
    ...loginMutationOptions,
    onSuccess: (tokens) => {
      setTokens(tokens.accessToken, tokens.refreshToken)
      return queryClient.invalidateQueries({ queryKey: sessionKeys.all })
    },
  })
}

export function useSignup() {
  return useMutation(signupMutationOptions)
}

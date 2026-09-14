import { create } from "zustand"
import { MOCK_ACCESS_TOKEN, mocksEnabled } from "../lib/mockConfig"

interface AuthState {
  accessToken: string | null
  setAccessToken: (accessToken: string | null) => void
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: mocksEnabled ? MOCK_ACCESS_TOKEN : null,
  setAccessToken: (accessToken) => set({ accessToken }),
}))

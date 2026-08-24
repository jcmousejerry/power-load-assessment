import { create } from 'zustand'
import type { LoginResult } from '../types'

type AuthState = {
  user: LoginResult | null
  setUser: (user: LoginResult) => void
  logout: () => void
}

const savedUser = localStorage.getItem('loadflex-user')

export const useAuthStore = create<AuthState>((set) => ({
  user: savedUser ? JSON.parse(savedUser) : null,
  setUser: (user) => {
    localStorage.setItem('loadflex-user', JSON.stringify(user))
    set({ user })
  },
  logout: () => {
    localStorage.removeItem('loadflex-user')
    set({ user: null })
  },
}))

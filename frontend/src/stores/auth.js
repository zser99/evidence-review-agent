import { defineStore } from 'pinia'
import api from '../api'

export const useAuth = defineStore('auth', {
  state: () => ({
    user: JSON.parse(sessionStorage.getItem('user') || 'null'),
    system: null,
  }),
  getters: {
    loggedIn: (s) => !!s.user,
    isStaff: (s) => !!s.user && (s.user.role === 'HR' || s.user.role === 'ADMIN'),
    isAdmin: (s) => !!s.user && s.user.role === 'ADMIN',
    roleLabel: (s) => ({ EMPLOYEE: '신청 구성원', HR: 'HR 담당자', ADMIN: '규정 관리자' })[s.user?.role] || '',
  },
  actions: {
    async login(username, password) {
      const { data } = await api.post('/auth/login', { username, password })
      sessionStorage.setItem('token', data.token)
      sessionStorage.setItem('user', JSON.stringify(data.user))
      this.user = data.user
      this.loadSystem()
    },
    logout() {
      sessionStorage.removeItem('token')
      sessionStorage.removeItem('user')
      this.user = null
      this.system = null
    },
    async loadSystem() {
      try {
        this.system = (await api.get('/system/info')).data
      } catch {
        this.system = null
      }
    },
  },
})

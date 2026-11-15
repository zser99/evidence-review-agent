import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'

const routes = [
  { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
  { path: '/', redirect: () => (useAuth().isStaff ? '/review' : '/applications') },
  { path: '/apply', component: () => import('./views/ApplyView.vue') },
  { path: '/applications', component: () => import('./views/MyApplicationsView.vue') },
  { path: '/applications/:id', component: () => import('./views/ApplicationDetailView.vue'), props: true },
  { path: '/review', component: () => import('./views/ReviewQueueView.vue'), meta: { staff: true } },
  { path: '/evaluation', component: () => import('./views/EvaluationView.vue'), meta: { staff: true } },
  { path: '/policies', component: () => import('./views/PoliciesView.vue'), meta: { staff: true } },
  { path: '/policies/:id', component: () => import('./views/PolicyDetailView.vue'), props: true, meta: { staff: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHistory(), routes })

// 화면 접근 제어는 편의용이다. 실제 권한은 서버가 모든 요청에서 다시 검증한다.
router.beforeEach((to) => {
  const auth = useAuth()
  if (!to.meta.public && !auth.loggedIn) return { path: '/login', query: { next: to.fullPath } }
  if (to.meta.staff && !auth.isStaff) return '/applications'
  return true
})

export default router

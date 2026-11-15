<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from './stores/auth'

const auth = useAuth()
const router = useRouter()

onMounted(() => {
  if (auth.loggedIn) auth.loadSystem()
})

const model = computed(() => {
  const d = auth.system?.ai?.decision
  if (!auth.system) return null
  if (!auth.system.aiAvailable) return { text: 'AI 서버 연결 안 됨', cls: 'text-bg-danger', tip: 'AI 서버에 연결할 수 없습니다.' }
  if (d?.provider === 'jev') return { text: `Jev · ${d.model}`, cls: 'text-bg-success', tip: 'TypeSafe Jev 결정 모델로 판정 중' }
  const name = d?.provider === 'openai-logprob' ? 'LLM 로그 확률' : '로컬 보정 분류기'
  return { text: `대체 모델 · ${name}`, cls: 'text-bg-warning', tip: 'Jev API 키가 없어 대체 모델로 판정 중입니다. 확신도 분기와 검증은 같은 방식으로 동작합니다.' }
})

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <nav v-if="auth.loggedIn" class="navbar navbar-expand-md app-nav navbar-dark py-2">
    <div class="container-fluid px-3 px-lg-4">
      <RouterLink class="navbar-brand" to="/"><i class="bi bi-patch-check-fill me-1"></i>증빙 검수 Agent</RouterLink>
      <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#nav"><span class="navbar-toggler-icon"></span></button>
      <div id="nav" class="collapse navbar-collapse">
        <ul class="navbar-nav me-auto gap-1">
          <template v-if="!auth.isStaff">
            <li class="nav-item"><RouterLink class="nav-link" to="/apply"><i class="bi bi-plus-circle me-1"></i>지원 신청</RouterLink></li>
            <li class="nav-item"><RouterLink class="nav-link" to="/applications"><i class="bi bi-list-check me-1"></i>내 신청</RouterLink></li>
          </template>
          <template v-else>
            <li class="nav-item"><RouterLink class="nav-link" to="/review"><i class="bi bi-inboxes me-1"></i>검토 대기</RouterLink></li>
            <li class="nav-item"><RouterLink class="nav-link" to="/evaluation"><i class="bi bi-bar-chart-line me-1"></i>검증 결과</RouterLink></li>
            <li class="nav-item"><RouterLink class="nav-link" to="/policies"><i class="bi bi-journal-text me-1"></i>규정 관리</RouterLink></li>
          </template>
        </ul>
        <span v-if="model" class="badge me-3" :class="model.cls" :title="model.tip"><i class="bi bi-cpu me-1"></i>{{ model.text }}</span>
        <span class="text-white me-3 small"><i class="bi bi-person-circle me-1"></i>{{ auth.user.name }} <span class="opacity-75">· {{ auth.roleLabel }}</span></span>
        <button class="btn btn-sm btn-outline-light" @click="logout">로그아웃</button>
      </div>
    </div>
  </nav>
  <main class="container-fluid px-3 px-lg-4 py-3" style="max-width: 1500px">
    <RouterView :key="$route.fullPath" />
  </main>
</template>

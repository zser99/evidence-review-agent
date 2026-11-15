<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const error = ref(null)
const busy = ref(false)

const demos = [
  { u: 'employee1', name: '김민준', role: '신청 구성원', icon: 'bi-person' },
  { u: 'hr1', name: '박지훈', role: 'HR 담당자', icon: 'bi-person-check' },
  { u: 'admin1', name: '최수아', role: '규정 관리자', icon: 'bi-gear' },
]

async function submit() {
  error.value = null
  busy.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    router.push(route.query.next || '/')
  } catch (e) {
    error.value = e.userMessage
  } finally {
    busy.value = false
  }
}
function fill(d) {
  username.value = d.u
  password.value = 'demo1234'
}
</script>

<template>
  <div class="row justify-content-center pt-5">
    <div class="col-12 col-sm-9 col-md-6 col-lg-4">
      <div class="text-center mb-4">
        <i class="bi bi-patch-check-fill" style="font-size: 2.6rem; color: var(--brand)"></i>
        <h1 class="h4 fw-bold mt-2 mb-1">증빙 검수 Agent</h1>
        <div class="small-muted">학자금·의료비 지원 증빙을 추출·대조하고 규정 충족 여부를 근거와 확신도로 판정합니다</div>
      </div>
      <form class="panel panel-body" @submit.prevent="submit">
        <div class="mb-3">
          <label class="form-label" for="u">아이디</label>
          <input id="u" v-model="username" class="form-control" autocomplete="username" required />
        </div>
        <div class="mb-3">
          <label class="form-label" for="p">비밀번호</label>
          <input id="p" v-model="password" type="password" class="form-control" autocomplete="current-password" required />
        </div>
        <div v-if="error" class="alert alert-danger py-2">{{ error }}</div>
        <button class="btn btn-primary w-100" :disabled="busy"><span v-if="busy" class="spinner-border spinner-border-sm me-1"></span>로그인</button>
      </form>
      <div class="panel panel-body mt-3">
        <div class="small-muted mb-2">데모 계정 (비밀번호 demo1234)</div>
        <div class="d-grid gap-2">
          <button v-for="d in demos" :key="d.u" type="button" class="btn btn-light border text-start" @click="fill(d)">
            <i class="bi me-2" :class="d.icon"></i><b>{{ d.role }}</b> <span class="small-muted">· {{ d.name }} ({{ d.u }})</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

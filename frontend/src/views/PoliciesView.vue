<script setup>
// 규정 관리: 지원 종류마다 '뽑을 항목 · 확인할 조건 · 필요한 서류'를 YAML로 정하고 버전으로 관리한다.
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { day, dt } from '../format'
import { useAuth } from '../stores/auth'

const auth = useAuth()
const router = useRouter()
const rows = ref([])
const loading = ref(true)
const error = ref(null)
const editor = ref({ open: false, yamlText: '', regulationText: '', note: '', errors: null, busy: false, baseId: '' })
const draft = ref({ busy: false, result: null })

const STATUS = { ACTIVE: { label: '시행 중', cls: 'tag-ok' }, DRAFT: { label: '검토 중', cls: 'tag-warn' }, RETIRED: { label: '이전 버전', cls: 'tag-mute' } }
const groups = computed(() => {
  const g = {}
  for (const r of rows.value) (g[r.supportType] ||= []).push(r)
  return g
})

async function load() {
  loading.value = true
  try {
    rows.value = (await api.get('/policies')).data
  } catch (e) {
    error.value = e.userMessage
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function copyFrom() {
  if (!editor.value.baseId) return
  const { data } = await api.get(`/policies/${editor.value.baseId}`)
  editor.value.yamlText = data.yamlText
  editor.value.regulationText = data.regulationText
  editor.value.errors = null
}
async function validate() {
  editor.value.busy = true
  try {
    editor.value.errors = (await api.post('/policies/validate', editor.value)).data.errors
  } catch (e) {
    editor.value.errors = [e.userMessage]
  } finally {
    editor.value.busy = false
  }
}
async function create() {
  editor.value.busy = true
  error.value = null
  try {
    const { data } = await api.post('/policies', editor.value)
    router.push(`/policies/${data.id}`)
  } catch (e) {
    editor.value.errors = e.userMessage.split('\n- ').slice(1).length ? e.userMessage.split('\n- ').slice(1) : [e.userMessage]
  } finally {
    editor.value.busy = false
  }
}
async function suggest() {
  draft.value = { busy: true, result: null }
  try {
    const m = /support_type:\s*([a-z0-9_]+)/.exec(editor.value.yamlText)
    draft.value.result = (await api.post('/policies/draft-judgments', { supportType: m ? m[1] : 'new', regulationText: editor.value.regulationText })).data
  } catch (e) {
    draft.value.result = { available: false, reason: e.userMessage }
  } finally {
    draft.value.busy = false
  }
}
</script>

<template>
  <div class="d-flex align-items-center mb-3">
    <h1 class="h5 fw-bold mb-0">규정 관리</h1>
    <button v-if="auth.isAdmin" class="btn btn-primary btn-sm ms-auto" @click="editor.open = !editor.open"><i class="bi bi-upload"></i> 새 버전 · 새 지원 종류 올리기</button>
  </div>
  <div v-if="error" class="alert alert-danger py-2">{{ error }}</div>

  <div v-if="editor.open" class="panel mb-3">
    <div class="panel-head"><i class="bi bi-file-earmark-code"></i>규정 올리기
      <span class="small-muted fw-normal">개정본은 ‘검토 중’으로 올라가 스키마 검증 → 조항 색인 → 정답 세트 회귀 테스트를 거친 뒤 시행합니다.</span></div>
    <div class="panel-body">
      <div class="d-flex flex-wrap gap-2 align-items-center mb-3">
        <label class="small-muted" for="base">기존 버전에서 가져오기</label>
        <select id="base" v-model="editor.baseId" class="form-select form-select-sm" style="width: auto" @change="copyFrom">
          <option value="">선택 안 함 (새로 작성)</option>
          <option v-for="r in rows" :key="r.id" :value="r.id">{{ r.name }} v{{ r.version }} ({{ STATUS[r.status].label }})</option>
        </select>
        <span class="small-muted">가져온 뒤 <span class="mono">version</span>을 올리고 바뀐 조건을 고치세요. 새 지원 종류는 <span class="mono">support_type</span>만 다르게 주면 됩니다.</span>
      </div>
      <div class="row g-3">
        <div class="col-12 col-xl-7">
          <label class="form-label mb-1" for="yaml">지원 종류 설정 (YAML) — 뽑을 항목 · 대조 · 정적 규칙 · Jev 판정 질문 · 필요한 서류</label>
          <textarea id="yaml" v-model="editor.yamlText" class="form-control code" rows="22" spellcheck="false"></textarea>
        </div>
        <div class="col-12 col-xl-5">
          <label class="form-label mb-1" for="reg">규정집 원문 — ‘제N조(제목) …’ 형식의 조항</label>
          <textarea id="reg" v-model="editor.regulationText" class="form-control" rows="14"></textarea>
          <label class="form-label mb-1 mt-2" for="note">개정 메모</label>
          <input id="note" v-model="editor.note" class="form-control" placeholder="예: 학기별 한도 500만원 → 600만원" />
          <button class="btn btn-sm btn-outline-secondary mt-3" :disabled="draft.busy || !editor.regulationText" @click="suggest">
            <span v-if="draft.busy" class="spinner-border spinner-border-sm me-1"></span><i class="bi bi-magic"></i> 규정집에서 Jev 질문 초안 제안받기 (LLM)</button>
          <div v-if="draft.result" class="mt-2">
            <div v-if="!draft.result.available" class="alert alert-warning py-2 small mb-0">{{ draft.result.reason }}</div>
            <pre v-else class="mono bg-light border rounded p-2 mb-0" style="max-height: 16rem; overflow: auto">{{ JSON.stringify({ judgments: draft.result.judgments, static_rule_candidates: draft.result.static_rule_candidates }, null, 1) }}</pre>
          </div>
        </div>
      </div>
      <div v-if="editor.errors" class="alert py-2 mt-3 mb-0" :class="editor.errors.length ? 'alert-danger' : 'alert-success'">
        <template v-if="editor.errors.length"><b>고쳐야 할 내용 {{ editor.errors.length }}건</b><ul class="mb-0 ps-3"><li v-for="(e, i) in editor.errors" :key="i">{{ e }}</li></ul></template>
        <template v-else><i class="bi bi-check-circle"></i> 스키마 검증을 통과했습니다.</template>
      </div>
      <div class="d-flex gap-2 mt-3">
        <button class="btn btn-outline-primary" :disabled="editor.busy" @click="validate">검증만 하기</button>
        <button class="btn btn-primary" :disabled="editor.busy || !editor.yamlText || !editor.regulationText" @click="create">검토 중 버전으로 올리기</button>
      </div>
    </div>
  </div>

  <div v-if="loading" class="text-muted"><span class="spinner-border spinner-border-sm me-2"></span>불러오는 중…</div>
  <div v-for="(list, type) in groups" :key="type" class="panel mb-3">
    <div class="panel-head"><i class="bi bi-journal-text"></i>{{ list[0].name }} <span class="mono small-muted">{{ type }}</span></div>
    <div class="table-responsive">
      <table class="table table-hover mb-0">
        <thead><tr><th>버전</th><th>상태</th><th>시행일</th><th>서류 · 대조 · 규칙 · Jev 질문</th><th>자동 판정 기준</th><th>조항 색인</th><th>회귀 테스트</th><th>등록</th></tr></thead>
        <tbody>
          <tr v-for="r in list" :key="r.id" class="clickable" @click="router.push(`/policies/${r.id}`)">
            <td class="fw-semibold">v{{ r.version }}</td>
            <td><span class="tag" :class="STATUS[r.status].cls">{{ STATUS[r.status].label }}</span></td>
            <td>{{ day(r.effectiveDate) }}</td>
            <td>{{ r.counts.documents }} · {{ r.counts.comparisons }} · {{ r.counts.staticRules }} · {{ r.counts.judgments }}</td>
            <td>{{ r.decision.autoThreshold }}</td>
            <td><span v-if="r.indexedAt" class="tag tag-ok">{{ r.clauseCount }}개 조항</span><span v-else class="tag tag-warn">대기 중</span></td>
            <td><span v-if="r.regression" class="tag" :class="r.regression.changed ? 'tag-warn' : 'tag-ok'">판정 변동 {{ r.regression.changed }}건</span><span v-else class="small-muted">—</span></td>
            <td class="small-muted">{{ r.createdBy }} · {{ dt(r.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

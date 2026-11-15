<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { VERDICT, day, dt } from '../format'
import { useAuth } from '../stores/auth'

const props = defineProps({ id: { type: String, required: true } })
const auth = useAuth()
const router = useRouter()
const p = ref(null)
const error = ref(null)
const notice = ref(null)
const tab = ref('overview')
const run = ref(null)
let timer = null

const STATUS = { ACTIVE: { label: '시행 중', cls: 'tag-ok' }, DRAFT: { label: '검토 중', cls: 'tag-warn' }, RETIRED: { label: '이전 버전', cls: 'tag-mute' } }
const TYPE = { noul: '예/아니오', choice: '보기 선택', score: '수준 평가' }

async function load() {
  try {
    p.value = (await api.get(`/policies/${props.id}`)).data
  } catch (e) {
    error.value = e.userMessage
  }
}
onMounted(load)
onBeforeUnmount(() => timer && clearInterval(timer))

async function regression() {
  error.value = null
  try {
    run.value = (await api.post(`/policies/${props.id}/regression`)).data
    timer = setInterval(async () => {
      run.value = (await api.get(`/evaluations/${run.value.id}`)).data
      if (run.value.status !== 'RUNNING') {
        clearInterval(timer)
        timer = null
        if (run.value.status === 'FAILED') error.value = '회귀 테스트 실패: ' + run.value.error
        run.value = null
        load()
      }
    }, 3000)
  } catch (e) {
    error.value = e.userMessage
  }
}
async function activate() {
  if (!confirm(`v${p.value.version}을(를) 시행합니다. 이후 접수되는 신청부터 이 규정으로 판정하고, 현재 시행 중인 버전은 ‘이전 버전’이 됩니다. 계속할까요?`)) return
  try {
    await api.post(`/policies/${props.id}/activate`)
    notice.value = '시행했습니다.'
    load()
  } catch (e) {
    error.value = e.userMessage
  }
}
async function remove() {
  if (!confirm('검토 중인 이 버전을 삭제할까요?')) return
  try {
    await api.delete(`/policies/${props.id}`)
    router.push('/policies')
  } catch (e) {
    error.value = e.userMessage
  }
}
</script>

<template>
  <div v-if="!p" class="text-muted"><span v-if="!error" class="spinner-border spinner-border-sm me-2"></span>{{ error || '불러오는 중…' }}</div>
  <template v-else>
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <RouterLink to="/policies" class="btn btn-sm btn-light border"><i class="bi bi-arrow-left"></i></RouterLink>
      <h1 class="h5 fw-bold mb-0">{{ p.name }} <span class="text-muted">v{{ p.version }}</span></h1>
      <span class="tag" :class="STATUS[p.status].cls">{{ STATUS[p.status].label }}</span>
      <span class="small-muted">시행일 {{ day(p.effectiveDate) }} · 등록 {{ p.createdBy }} {{ dt(p.createdAt) }}<span v-if="p.note"> · {{ p.note }}</span></span>
      <div v-if="auth.isAdmin && p.status === 'DRAFT'" class="ms-auto d-flex gap-2">
        <button class="btn btn-sm btn-outline-primary" :disabled="!!run" @click="regression"><i class="bi bi-arrow-repeat"></i> 정답 세트 회귀 테스트</button>
        <button class="btn btn-sm btn-success" :disabled="!!run" @click="activate"><i class="bi bi-check2-circle"></i> 시행</button>
        <button class="btn btn-sm btn-outline-danger" :disabled="!!run" @click="remove">삭제</button>
      </div>
    </div>
    <div v-if="error" class="alert alert-danger py-2">{{ error }}</div>
    <div v-if="notice" class="alert alert-success py-2">{{ notice }}</div>
    <div v-if="run" class="panel panel-body mb-3">
      <div class="d-flex mb-1"><b><span class="spinner-border spinner-border-sm me-2"></span>개정 규정으로 정답 세트를 다시 검수하는 중…</b><span class="ms-auto">{{ run.progressDone }} / {{ run.progressTotal || '?' }}</span></div>
      <div class="progress" style="height: 8px"><div class="progress-bar" :style="{ width: (run.progressTotal ? (run.progressDone / run.progressTotal) * 100 : 3) + '%' }"></div></div>
    </div>

    <div v-if="p.regression" class="panel mb-3">
      <div class="panel-head"><i class="bi bi-arrow-repeat"></i>회귀 테스트 결과
        <span class="tag" :class="p.regression.changed ? 'tag-warn' : 'tag-ok'">판정이 바뀐 건 {{ p.regression.changed }} / {{ p.regression.total }}</span>
        <span class="small-muted fw-normal ms-auto">기준 v{{ p.regression.baseVersion ?? '없음' }} → v{{ p.regression.version }} · 정답 {{ p.regression.beforeCorrect ?? '—' }} → {{ p.regression.afterCorrect }} · 오답 {{ p.regression.afterWrong }} · 확인 {{ p.regression.afterReview }} · {{ dt(p.regression.at) }}</span>
      </div>
      <div v-if="p.regression.changes.length" class="table-responsive">
        <table class="table table-sm mb-0">
          <thead><tr><th>건</th><th>내용</th><th>정답</th><th>시행 중 버전</th><th>이 버전</th><th>이 버전의 반려 사유</th></tr></thead>
          <tbody>
            <tr v-for="c in p.regression.changes" :key="c.id">
              <td class="mono">{{ c.id }}</td><td>{{ c.title }}</td>
              <td><span class="tag" :class="VERDICT[c.expected].cls">{{ VERDICT[c.expected].label }}</span></td>
              <td><span class="tag" :class="VERDICT[c.before].cls">{{ VERDICT[c.before].label }}</span></td>
              <td><span class="tag" :class="VERDICT[c.after].cls">{{ VERDICT[c.after].label }}</span></td>
              <td class="small">{{ (c.afterIssues || []).join(', ') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="panel-body small-muted">정답 세트의 모든 건이 시행 중 버전과 같은 판정을 받았습니다.</div>
      <div class="panel-body small-muted pt-0">정답 세트의 정답은 시행 중 규정 기준입니다. 규정이 실제로 바뀌어 판정이 달라진 건은 의도한 변화인지 확인한 뒤 시행하세요.</div>
    </div>

    <ul class="nav nav-tabs">
      <li v-for="t in [['overview', '구성'], ['clauses', '규정집 조항'], ['yaml', '설정 원문(YAML)']]" :key="t[0]" class="nav-item">
        <a class="nav-link" :class="{ active: tab === t[0] }" href="#" @click.prevent="tab = t[0]">{{ t[1] }}</a></li>
    </ul>
    <div class="panel" style="border-top: 0; border-top-left-radius: 0">
      <div v-if="tab === 'overview'" class="panel-body">
        <div class="row g-4">
          <div class="col-12 col-xl-6">
            <h2 class="h6 fw-bold">필요한 서류와 뽑을 항목</h2>
            <div v-for="d in p.definition.documents" :key="d.id" class="mb-3">
              <div><b>{{ d.name }}</b> <span class="tag" :class="d.required ? 'tag-bad' : 'tag-mute'">{{ d.required ? '필수' : '조건부' }}</span>
                <span v-if="d.requiredWhen" class="mono small-muted ms-1">{{ d.requiredWhen }}</span></div>
              <div class="small-muted mb-1">{{ d.description }}</div>
              <span v-for="f in d.fields" :key="f.key" class="tag tag-mute me-1 mb-1">{{ f.label }}<span v-if="f.required" class="text-danger">*</span></span>
              <span v-for="t in d.tables" :key="t.key" class="tag tag-info me-1 mb-1">표: {{ t.label }}</span>
            </div>
            <h2 class="h6 fw-bold mt-4">신청 내용 ↔ 증빙 대조</h2>
            <table class="table table-sm"><thead><tr><th>항목</th><th>신청·구성원 값</th><th>증빙 값</th><th>방식</th></tr></thead>
              <tbody><tr v-for="c in p.comparisons" :key="c.id"><td>{{ c.label }}</td><td class="mono">{{ c.left }}</td><td class="mono">{{ c.right }}</td><td>{{ c.method }}</td></tr></tbody></table>
          </div>
          <div class="col-12 col-xl-6">
            <h2 class="h6 fw-bold">코드로 확정하는 정적 규칙</h2>
            <table class="table table-sm"><thead><tr><th>규칙</th><th>조항</th><th>식</th></tr></thead>
              <tbody><tr v-for="s in p.staticRules" :key="s.id"><td>{{ s.label }}</td><td>{{ s.clause }}</td><td class="mono text-break">{{ s.expr }}</td></tr></tbody></table>
            <h2 class="h6 fw-bold mt-4">결정 모델(Jev)에 묻는 조건</h2>
            <div v-for="j in p.judgments" :key="j.id" class="border rounded p-2 mb-2">
              <div><b>{{ j.label }}</b> <span class="tag tag-info">{{ j.type }} · {{ TYPE[j.type] }}</span> <span class="tag tag-mute">{{ (j.clauseRefs || []).join(', ') }}</span>
                <span v-if="(j.dependsOn || []).length" class="tag tag-warn" title="앞 판정 결과가 필요해 요청을 나눠 보냅니다">선행: {{ j.dependsOn.join(', ') }}</span></div>
              <div class="small mt-1">{{ j.instructions }}</div>
              <div class="small-muted mt-1">
                <template v-if="Array.isArray(j.criteria)"><div v-for="(c, i) in j.criteria" :key="i">수준 {{ i }}: {{ c }}</div></template>
                <template v-else-if="j.criteria"><div v-for="(v, k) in j.criteria" :key="k"><span class="mono">{{ k }}</span>: {{ v }}</div></template>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-else-if="tab === 'clauses'" class="panel-body">
        <div v-if="!p.clauses?.length" class="text-muted">아직 색인되지 않았습니다.</div>
        <div v-for="c in p.clauses" :key="c.clause_id" class="clause mb-2"><b>{{ c.clause_id }}({{ c.title }})</b><br />{{ c.text }}</div>
      </div>
      <div v-else class="panel-body"><pre class="mono mb-0" style="white-space: pre-wrap">{{ p.yamlText }}</pre></div>
    </div>
  </template>
</template>

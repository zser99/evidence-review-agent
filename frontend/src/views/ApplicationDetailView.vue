<script setup>
// 신청 건 화면. 작성·보완 중이면 증빙 업로드와 즉시 대조를, 검수 뒤에는 최종 판정·대조표·조건별 판정·근거를 보여 준다.
// 구성원(본인 건)과 HR 담당자가 같은 화면을 쓰고, HR에게는 결정 패널과 감사 로그가 더 보인다.
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import ConfBar from '../components/ConfBar.vue'
import DocViewer from '../components/DocViewer.vue'
import FormFields from '../components/FormFields.vue'
import { AUDIT, RESULT, STATUS_CLS, VERDICT, dt, pct } from '../format'
import { useAuth } from '../stores/auth'

const props = defineProps({ id: { type: String, required: true } })
const auth = useAuth()

const d = ref(null)
const loading = ref(true)
const error = ref(null)
const notice = ref(null)
const form = ref({})
const formDirty = ref(false)
const busy = ref(false)
const uploading = ref(null)
const dragOver = ref(null)
const activeDoc = ref(null)
const activeKey = ref(null)
const expanded = ref({})
const pastRun = ref(null)

const decision = ref({ comment: '', guidance: '', busy: false, polishNote: null })

const isOwner = computed(() => d.value && auth.user.id === d.value.applicant.id)
const canEdit = computed(() => d.value?.editable && isOwner.value)
const review = computed(() => pastRun.value || d.value?.review || null)
const verdict = computed(() => (review.value ? VERDICT[review.value.verdict] : null))
const threshold = computed(() => review.value?.thresholds?.auto ?? d.value?.definition?.decision?.autoThreshold ?? 0.85)
const docsWithFile = computed(() => (d.value?.documents || []).filter((x) => x.file))
const currentDoc = computed(() => docsWithFile.value.find((x) => x.id === activeDoc.value) || docsWithFile.value[0] || null)
const comparisons = computed(() => (canEdit.value && !pastRun.value ? d.value.precheck.comparisons : review.value?.comparisons || d.value?.precheck?.comparisons || []))
const missingDocs = computed(() => (canEdit.value && !pastRun.value ? d.value.precheck.missingDocuments : review.value?.missingDocuments || []))
const conditions = computed(() => {
  if (!review.value) return []
  const s = (review.value.staticRules || []).map((r) => ({ ...r, kind: 'static', result: r.status }))
  const j = (review.value.judgments || []).map((r) => ({ ...r, kind: 'judgment', result: r.decision }))
  return [...s, ...j]
})
const finalText = computed(() => {
  const a = d.value
  if (!a?.finalDecision) return null
  return `${a.finalDecision === 'APPROVE' ? '승인' : '반려'} 확정 · ${a.decidedBy} · ${dt(a.decidedAt)}`
})

function apply(data) {
  d.value = data
  form.value = { ...data.form }
  formDirty.value = false
  if (!activeDoc.value || !data.documents.some((x) => x.id === activeDoc.value && x.file)) {
    activeDoc.value = data.documents.find((x) => x.file)?.id || null
  }
  decision.value.guidance = data.guidance || ''
}

async function load() {
  loading.value = true
  try {
    apply((await api.get(`/applications/${props.id}`)).data)
  } catch (e) {
    error.value = e.userMessage
  } finally {
    loading.value = false
  }
}
onMounted(load)

function onForm(v) {
  form.value = v
  formDirty.value = true
}
async function saveForm() {
  apply((await api.put(`/applications/${props.id}`, { form: form.value })).data)
}
async function guard(fn) {
  error.value = null
  notice.value = null
  try {
    await fn()
  } catch (e) {
    error.value = e.userMessage || '처리 중 오류가 발생했습니다.'
  }
}

async function upload(docId, file) {
  if (!file) return
  if (file.size > 15 * 1024 * 1024) {
    error.value = '파일이 너무 큽니다. 15MB 이하의 파일을 올려 주세요.'
    return
  }
  uploading.value = docId
  await guard(async () => {
    if (formDirty.value) await saveForm()
    const fd = new FormData()
    fd.append('docId', docId)
    fd.append('file', file)
    apply((await api.post(`/applications/${props.id}/evidence`, fd)).data)
    activeDoc.value = docId
    activeKey.value = null
    pastRun.value = null
  })
  uploading.value = null
}
function onDrop(docId, ev) {
  dragOver.value = null
  upload(docId, ev.dataTransfer.files[0])
}
async function removeFile(doc) {
  await guard(async () => apply((await api.delete(`/applications/${props.id}/evidence/${doc.file.id}`)).data))
}

async function submit() {
  busy.value = true
  await guard(async () => {
    if (formDirty.value) await saveForm()
    apply((await api.post(`/applications/${props.id}/submit`)).data)
    pastRun.value = null
    window.scrollTo({ top: 0, behavior: 'smooth' })
  })
  busy.value = false
}

async function decide(kind) {
  decision.value.busy = true
  await guard(async () => {
    const body = { decision: kind, comment: decision.value.comment, guidance: kind === 'REJECT' ? decision.value.guidance : null }
    apply((await api.post(`/review/applications/${props.id}/decision`, body)).data)
    notice.value = kind === 'APPROVE' ? '승인으로 확정했습니다.' : '반려로 확정했습니다.'
    decision.value.comment = ''
  })
  decision.value.busy = false
}
async function polish() {
  decision.value.busy = true
  decision.value.polishNote = null
  await guard(async () => {
    const { data } = await api.post(`/review/applications/${props.id}/guidance-polish`, { draft: decision.value.guidance })
    if (data.available) decision.value.guidance = data.text
    else decision.value.polishNote = data.reason || 'LLM을 사용할 수 없어 초안을 그대로 둡니다.'
  })
  decision.value.busy = false
}

async function showRun(runNo) {
  if (d.value.review && runNo === d.value.review.runNo) {
    pastRun.value = null
    return
  }
  await guard(async () => {
    pastRun.value = (await api.get(`/applications/${props.id}/runs/${runNo}`)).data
  })
}
function pickField(key) {
  activeKey.value = key
}
function focusComparison(c) {
  if (!c.docId) return
  activeDoc.value = c.docId
  activeKey.value = c.rightPath.split('.').pop()
}
const res = (s) => RESULT[s] || { label: s, cls: 'tag-mute' }
const probs = (j) => Object.entries(j.probabilities || {}).map(([k, v]) => ({ k, label: j.optionLabels?.[k] || (j.type === 'noul' ? (k === 'true' ? '예' : '아니오') : j.type === 'score' ? `수준 ${k}` : k), v }))
const auditDetail = (l) => {
  const x = l.detail || {}
  if (l.action === 'REVIEW_COMPLETED') return `${x.runNo}회차 · ${VERDICT[x.verdict]?.label} · 확신도 ${pct(x.confidence)} · ${x.provider}/${x.model} · 규정 v${x.policyVersion}`
  if (l.action === 'HR_DECISION') return `${x.decision === 'APPROVE' ? '승인' : '반려'}${x.overridden ? ' (자동 판정과 다름)' : ''}${x.comment ? ' — ' + x.comment : ''}`
  if (l.action === 'EVIDENCE_UPLOADED') return `${x.filename} · ${x.docId} · sha256 ${String(x.sha256).slice(0, 12)}…`
  return Object.keys(x).length ? JSON.stringify(x) : ''
}
</script>

<template>
  <div v-if="loading" class="text-muted py-5 text-center"><span class="spinner-border spinner-border-sm me-2"></span>불러오는 중…</div>
  <div v-else-if="!d" class="alert alert-danger">{{ error || '신청 건을 찾을 수 없습니다.' }}</div>
  <template v-else>
    <!-- 머리말 -->
    <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
      <RouterLink :to="auth.isStaff ? '/review' : '/applications'" class="btn btn-sm btn-light border"><i class="bi bi-arrow-left"></i></RouterLink>
      <h1 class="h5 fw-bold mb-0">{{ d.supportName }} 신청</h1>
      <span class="mono text-muted">{{ d.applicationNo }}</span>
      <span class="tag" :class="STATUS_CLS[d.status]">{{ d.statusLabel }}</span>
      <span class="tag tag-info" title="이 건에 적용된 규정 버전">규정 v{{ d.policy.version }}</span>
      <span class="ms-auto small-muted">신청자 {{ d.applicant.name }} · {{ d.applicant.department }} <span v-if="d.submittedAt">· 제출 {{ dt(d.submittedAt) }}</span></span>
    </div>
    <div v-if="error" class="alert alert-danger py-2" style="white-space: pre-wrap">{{ error }}</div>
    <div v-if="notice" class="alert alert-success py-2">{{ notice }}</div>

    <!-- 최종 판정 + 보완 안내 -->
    <div v-if="review && verdict" class="verdict mb-3" :class="'verdict-' + review.verdict">
      <div class="d-flex flex-wrap align-items-center gap-3 mb-2">
        <h2><i class="bi me-1" :class="verdict.icon"></i>{{ verdict.label }}</h2>
        <span class="d-flex align-items-center gap-2"><span class="small-muted">확신도</span><ConfBar :value="review.confidence" :threshold="threshold" /></span>
        <span v-if="pastRun" class="tag tag-warn">{{ pastRun.runNo }}회차 검수 기록을 보는 중</span>
        <span v-if="finalText && !pastRun" class="tag tag-mute"><i class="bi bi-person-check"></i> {{ finalText }}</span>
        <span class="ms-auto small-muted" :title="(review.decisionModel?.requestHashes || []).join(', ')">
          <i class="bi bi-cpu"></i> {{ review.decisionModel?.provider || '-' }} · {{ review.decisionModel?.model || '-' }} · {{ dt(review.generatedAt) }}
        </span>
      </div>
      <pre class="guidance">{{ pastRun ? pastRun.guidance : d.guidance }}</pre>
      <div v-if="d.decisionComment && !pastRun" class="small mt-2"><b>담당자 의견:</b> {{ d.decisionComment }}</div>
    </div>

    <!-- HR 결정 -->
    <div v-if="auth.isStaff && d.status !== 'DRAFT'" class="panel mb-3">
      <div class="panel-head"><i class="bi bi-person-check"></i>담당자 결정
        <span class="small-muted fw-normal ms-2">자동 판정과 다르게 결정할 때는 사유를 꼭 남깁니다. 결정은 감사 로그에 기록됩니다.</span>
      </div>
      <div class="panel-body">
        <div class="row g-3">
          <div class="col-12 col-lg-5">
            <label class="form-label mb-1" for="cm">결정 사유·의견</label>
            <textarea id="cm" v-model="decision.comment" class="form-control" rows="4" placeholder="확인한 내용과 결정 사유"></textarea>
          </div>
          <div class="col-12 col-lg-7">
            <label class="form-label mb-1 d-flex" for="gd">구성원에게 보낼 안내(반려 시)
              <button class="btn btn-sm btn-outline-secondary py-0 ms-auto" :disabled="decision.busy" @click="polish"><i class="bi bi-magic"></i> AI로 문구 다듬기</button>
            </label>
            <textarea id="gd" v-model="decision.guidance" class="form-control" rows="4"></textarea>
            <div v-if="decision.polishNote" class="form-text text-warning-emphasis">{{ decision.polishNote }}</div>
          </div>
        </div>
        <div class="d-flex gap-2 mt-3">
          <button class="btn btn-success" :disabled="decision.busy" @click="decide('APPROVE')"><i class="bi bi-check-lg"></i> 승인 확정</button>
          <button class="btn btn-danger" :disabled="decision.busy" @click="decide('REJECT')"><i class="bi bi-x-lg"></i> 반려 확정</button>
        </div>
      </div>
    </div>

    <div class="row g-3">
      <!-- 왼쪽: 증빙 원본 -->
      <div class="col-12 col-xl-5">
        <div class="panel" style="position: sticky; top: 0.75rem">
          <div class="panel-head"><i class="bi bi-file-earmark-image"></i>증빙 원본
            <div v-if="docsWithFile.length > 1" class="btn-group btn-group-sm ms-auto">
              <button v-for="x in docsWithFile" :key="x.id" class="btn" :class="currentDoc?.id === x.id ? 'btn-primary' : 'btn-outline-primary'" @click="activeDoc = x.id; activeKey = null">{{ x.name }}</button>
            </div>
          </div>
          <div class="panel-body">
            <DocViewer :app-id="d.id" :file="currentDoc?.file || null" :active-key="activeKey" @pick="pickField" />
          </div>
        </div>
      </div>

      <!-- 오른쪽 -->
      <div class="col-12 col-xl-7">
        <!-- 신청 내용 -->
        <div class="panel mb-3">
          <div class="panel-head"><i class="bi bi-card-text"></i>신청 내용
            <button v-if="canEdit && formDirty" class="btn btn-sm btn-outline-primary ms-auto" @click="guard(saveForm)">변경 내용 저장</button>
          </div>
          <div class="panel-body">
            <FormFields v-if="canEdit" :fields="d.definition.form" :model-value="form" @update:model-value="onForm" />
            <div v-else class="row g-2">
              <div v-for="f in d.formView" :key="f.key" class="col-6 col-md-4"><div class="small-muted">{{ f.label }}</div><div class="fw-semibold">{{ f.display ?? '-' }}{{ f.type === 'money' && f.display ? '원' : '' }}</div></div>
            </div>
          </div>
        </div>

        <!-- 증빙 업로드 -->
        <div v-if="canEdit" class="panel mb-3">
          <div class="panel-head"><i class="bi bi-cloud-arrow-up"></i>증빙 올리기 <span class="small-muted fw-normal">PDF · PNG · JPG, 15MB 이하. 올리면 바로 항목을 읽어 옵니다.</span></div>
          <div class="panel-body d-grid gap-3">
            <div v-for="doc in d.documents" :key="doc.id">
              <div class="d-flex align-items-center gap-2 mb-1">
                <span class="fw-semibold">{{ doc.name }}</span>
                <span class="tag" :class="doc.required ? 'tag-bad' : 'tag-mute'">{{ doc.required ? '필수' : '해당 시' }}</span>
                <span v-if="doc.file && doc.status === 'OK'" class="tag tag-ok"><i class="bi bi-check"></i> 확인됨</span>
                <span v-else-if="doc.status === 'WRONG_TYPE'" class="tag tag-bad">인정되지 않는 서류</span>
                <span v-else-if="doc.status === 'MISSING'" class="tag tag-warn">아직 없음</span>
                <button v-if="doc.file" class="btn btn-sm btn-link text-danger ms-auto p-0" @click="removeFile(doc)">삭제</button>
              </div>
              <div class="small-muted mb-1">{{ doc.description }}</div>
              <div v-if="doc.status === 'WRONG_TYPE'" class="alert alert-danger py-2 mb-2 small">{{ doc.reason }} 올린 파일의 제목: “{{ doc.file?.title || '확인 안 됨' }}”</div>
              <label class="dropzone d-block" :class="{ over: dragOver === doc.id }" @dragover.prevent="dragOver = doc.id" @dragleave="dragOver = null" @drop.prevent="onDrop(doc.id, $event)">
                <input type="file" class="d-none" accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg" :disabled="uploading !== null" @change="upload(doc.id, $event.target.files[0]); $event.target.value = ''" />
                <span v-if="uploading === doc.id"><span class="spinner-border spinner-border-sm me-2"></span>증빙을 읽는 중…</span>
                <span v-else-if="doc.file"><i class="bi bi-arrow-repeat"></i> {{ doc.file.filename }} — 다른 파일로 교체하려면 클릭하거나 끌어다 놓으세요</span>
                <span v-else><i class="bi bi-upload"></i> 클릭하거나 파일을 끌어다 놓으세요</span>
              </label>
            </div>
          </div>
        </div>

        <!-- 추출 항목 -->
        <div v-if="currentDoc" class="panel mb-3">
          <div class="panel-head"><i class="bi bi-list-columns-reverse"></i>증빙에서 뽑은 항목 — {{ currentDoc.name }}
            <span class="small-muted fw-normal ms-auto">{{ currentDoc.file.engine === 'pdf-text' ? 'PDF 텍스트' : 'OCR' }} · {{ currentDoc.file.elapsedMs }}ms · 행을 누르면 원본 위치 표시</span>
          </div>
          <div class="table-responsive">
            <table class="table table-sm table-hover mb-0">
              <thead><tr><th>항목</th><th>추출값</th><th>인식 신뢰도</th><th>상태</th></tr></thead>
              <tbody>
                <tr v-for="f in currentDoc.file.fields" :key="f.key" class="field-row" :class="{ active: activeKey === f.key }" @click="pickField(f.key)">
                  <td>{{ f.label }} <span v-if="f.required" class="text-danger">*</span></td>
                  <td class="fw-semibold">{{ f.display ?? '—' }}{{ f.type === 'money' && f.display ? '원' : '' }}</td>
                  <td><ConfBar v-if="f.status !== 'NOT_FOUND'" :value="f.confidence" :threshold="d.definition.decision.ocrMinConfidence" /></td>
                  <td><span class="tag" :class="res(f.status).cls">{{ res(f.status).label }}</span></td>
                </tr>
                <tr v-for="t in currentDoc.file.tables" :key="t.key">
                  <td>{{ t.label }}</td>
                  <td colspan="3">
                    <span v-if="!t.rows.length" class="small-muted">찾지 못함</span>
                    <span v-for="(r, i) in t.rows" :key="i" class="tag tag-mute me-1">{{ t.columns.map((c) => (c.type === 'money' && r[c.key] != null ? Number(r[c.key]).toLocaleString() + '원' : r[c.key])).join(' · ') }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 대조표 -->
        <div class="panel mb-3">
          <div class="panel-head"><i class="bi bi-arrow-left-right"></i>신청 내용 ↔ 증빙 대조
            <span v-if="canEdit && !pastRun" class="small-muted fw-normal ms-auto">증빙을 올리거나 신청 내용을 저장하면 바로 갱신됩니다</span>
          </div>
          <div v-if="missingDocs.length" class="alert alert-warning m-3 mb-0 py-2">
            <b><i class="bi bi-exclamation-triangle"></i> 빠진 서류</b>
            <ul class="mb-0 ps-3"><li v-for="m in missingDocs" :key="m.id"><b>{{ m.name }}</b> — {{ m.reason }}</li></ul>
          </div>
          <div class="table-responsive">
            <table class="table table-sm mb-0">
              <thead><tr><th>항목</th><th>신청 내용</th><th>증빙 값</th><th>결과</th></tr></thead>
              <tbody>
                <tr v-if="!comparisons.length"><td colspan="4" class="text-center text-muted py-3">증빙을 올리면 대조 결과가 표시됩니다.</td></tr>
                <tr v-for="c in comparisons" :key="c.id" class="field-row" @click="focusComparison(c)">
                  <td>{{ c.label }} <span v-if="c.clause" class="small-muted">({{ c.clause }})</span></td>
                  <td>{{ c.leftDisplay ?? '—' }}</td>
                  <td>{{ c.rightDisplay ?? '—' }} <span class="small-muted d-block">{{ c.rightLabel }}</span></td>
                  <td><span class="tag" :class="res(c.status).cls">{{ res(c.status).label }}</span>
                    <div v-if="c.message && c.status !== 'MATCH'" class="small text-muted mt-1" style="max-width: 22rem">{{ c.message }}</div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="canEdit" class="d-flex align-items-center gap-3 mb-3">
          <div class="small-muted">제출하면 규정 조건을 검수해 승인 · 반려 · 담당자 확인으로 판정합니다.</div>
          <button class="btn btn-primary btn-lg ms-auto" :disabled="busy || uploading !== null" @click="submit">
            <span v-if="busy" class="spinner-border spinner-border-sm me-1"></span>{{ d.status === 'DRAFT' ? '제출하고 검수 받기' : '보완해서 다시 제출' }}
          </button>
        </div>

        <!-- 규정 조건별 판정 -->
        <div v-if="review" class="panel mb-3">
          <div class="panel-head"><i class="bi bi-clipboard2-check"></i>규정 조건별 판정
            <span class="small-muted fw-normal ms-auto">자동 판정 기준 확신도 {{ pct(threshold, 0) }} · 미만이면 담당자 확인</span>
          </div>
          <div class="table-responsive">
            <table class="table table-sm mb-0 cond-table">
              <thead><tr><th>조건</th><th>판정 방식</th><th>결과</th><th>확신도</th><th>근거 조항</th><th>근거 값</th></tr></thead>
              <tbody>
                <template v-for="c in conditions" :key="c.id">
                  <tr :class="{ clickable: c.kind === 'judgment' }" @click="c.kind === 'judgment' && (expanded[c.id] = !expanded[c.id])">
                    <td><i v-if="c.kind === 'judgment'" class="bi me-1" :class="expanded[c.id] ? 'bi-chevron-down' : 'bi-chevron-right'"></i>{{ c.label }}</td>
                    <td><span class="tag" :class="c.kind === 'judgment' ? 'tag-info' : 'tag-mute'">{{ c.kind === 'judgment' ? '결정 모델 · ' + c.type : '코드 규칙' }}</span></td>
                    <td><span class="tag" :class="res(c.result).cls">{{ res(c.result).label }}</span></td>
                    <td><ConfBar v-if="c.kind === 'judgment' && c.status === 'ok'" :value="c.confidence" :threshold="c.threshold" /><span v-else class="small-muted">{{ c.kind === 'static' ? '확정' : '—' }}</span></td>
                    <td>{{ c.kind === 'judgment' ? (c.clauseRefs || []).join(', ') : c.clause || '—' }}</td>
                    <td class="small">
                      <template v-if="c.kind === 'judgment'"><b v-if="c.answerLabel">{{ c.answerLabel }}</b> <span class="text-muted">{{ Object.entries(c.inputs || {}).map(([k, v]) => `${k.replaceAll('_', ' ')}: ${v}`).join(' / ') }}</span></template>
                      <template v-else><span class="text-muted">{{ (c.evidence || []).map((e) => `${e.label}: ${e.value ?? '—'}`).join(' / ') }}</span></template>
                      <div v-if="c.message || c.reason" class="text-danger-emphasis">{{ c.message || c.reason }}</div>
                    </td>
                  </tr>
                  <tr v-if="c.kind === 'judgment' && expanded[c.id]">
                    <td colspan="6" class="bg-light">
                      <div class="row g-3 p-2">
                        <div class="col-12 col-lg-6">
                          <div class="small-muted mb-1">결정 모델에 보낸 질문 (개인정보 마스킹 후 · {{ c.phase }}차 요청)</div>
                          <pre class="guidance small mb-2">{{ c.question }}</pre>
                          <div class="small-muted mb-1">답의 확률 분포</div>
                          <div v-for="p in probs(c)" :key="p.k" class="d-flex align-items-center gap-2 small mb-1">
                            <span style="width: 9rem" class="text-truncate" :title="p.label">{{ p.label }}</span>
                            <div class="bar flex-grow-1"><div :style="{ width: p.v * 100 + '%', background: '#1f4e79' }"></div></div>
                            <span class="mono" style="width: 3.2rem; text-align: right">{{ (p.v * 100).toFixed(1) }}%</span>
                          </div>
                          <div class="small-muted mt-2">충족 확률 {{ pct(c.passProbability) }} · 확신도 {{ pct(c.confidence) }}<span v-if="c.nativeConfidence != null"> · 모델 confidence(분포 집중도) {{ Number(c.nativeConfidence).toFixed(2) }}</span></div>
                        </div>
                        <div class="col-12 col-lg-6">
                          <div class="small-muted mb-1">근거가 된 규정 조항</div>
                          <div v-for="cl in c.clauses || []" :key="cl.clause_id" class="clause mb-2">
                            <div class="d-flex"><b>{{ cl.clause_id }}({{ cl.title }})</b><span class="tag tag-mute ms-auto">{{ cl.source }}<span v-if="cl.source !== '지정 조항'"> {{ cl.score }}</span></span></div>
                            {{ cl.text }}
                          </div>
                          <div v-if="!(c.clauses || []).length" class="small-muted">조항 정보 없음</div>
                        </div>
                      </div>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
          <div v-if="review.decisionModel?.warnings?.length" class="alert alert-warning m-3 py-2 small">{{ review.decisionModel.warnings.join(' / ') }}</div>
        </div>

        <!-- 검수 이력 · 감사 로그 -->
        <div v-if="d.runs.length" class="panel mb-3">
          <div class="panel-head"><i class="bi bi-clock-history"></i>검수 이력 <span class="small-muted fw-normal">판정 당시의 규정 버전·근거·모델을 그대로 보관합니다. 회차를 누르면 그때의 결과를 다시 봅니다.</span></div>
          <div class="table-responsive">
            <table class="table table-sm table-hover mb-0">
              <thead><tr><th>회차</th><th>판정</th><th>확신도</th><th>결정 모델</th><th>구분</th><th>일시</th></tr></thead>
              <tbody>
                <tr v-for="r in d.runs" :key="r.runNo" class="clickable" :class="{ 'table-warning': pastRun?.runNo === r.runNo }" @click="showRun(r.runNo)">
                  <td>{{ r.runNo }}회차</td>
                  <td><span class="tag" :class="VERDICT[r.verdict]?.cls">{{ VERDICT[r.verdict]?.label }}</span></td>
                  <td><ConfBar :value="r.confidence" :threshold="threshold" /></td>
                  <td class="small">{{ r.provider }} · {{ r.model }}</td>
                  <td class="small">{{ r.trigger === 'RESUBMIT' ? '보완 제출' : '최초 제출' }}</td>
                  <td class="small-muted">{{ dt(r.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <div v-if="d.audit" class="panel mb-3">
          <div class="panel-head"><i class="bi bi-shield-check"></i>감사 로그</div>
          <div class="table-responsive">
            <table class="table table-sm mb-0">
              <thead><tr><th>일시</th><th>수행자</th><th>행위</th><th>내용</th></tr></thead>
              <tbody>
                <tr v-for="(l, i) in d.audit" :key="i">
                  <td class="small-muted text-nowrap">{{ dt(l.at) }}</td><td class="text-nowrap">{{ l.actor }} <span class="small-muted">{{ l.role }}</span></td>
                  <td class="text-nowrap">{{ AUDIT[l.action] || l.action }}</td><td class="small text-break">{{ auditDetail(l) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </template>
</template>

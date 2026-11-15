<script setup>
// 검증 결과: 정답 세트로 잰 항목 추출 정확도, 최종 판정 정확도, 확신도 구간별 실제 정답률, 담당자 확인 비율.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '../api'
import ConfBar from '../components/ConfBar.vue'
import { VERDICT, dt, pct } from '../format'

const latest = ref(null)
const running = ref(null)
const loading = ref(true)
const error = ref(null)
const filter = ref('issues')
const open = ref({})
let timer = null

const s = computed(() => latest.value?.summary || null)
const cal = computed(() => s.value?.calibration || null)
const cases = computed(() => {
  const all = latest.value?.cases || []
  if (filter.value === 'all') return all
  if (filter.value === 'issues') return all.filter((c) => c.outcome !== 'CORRECT' || c.extractionMisses.length)
  return all.filter((c) => c.supportType === filter.value)
})
const SCENARIO = {
  approve: '정상 승인', approve_grad_self: '본인 대학원(승인)', approve_scan: '약한 스캔 잡음', approve_limit: '한도와 같은 금액', approve_poor_scan: '심한 저화질 스캔',
  approve_ambiguous: '판단이 어려운 정상 건', amount_mismatch: '금액 불일치', semester_mismatch: '학기 불일치', missing_document: '서류 누락', name_mismatch: '성명 불일치',
  over_limit: '한도 초과', deadline: '신청 기한 경과', ineligible_institution: '비대상 교육기관', ineligible_items: '비대상 항목', informal_document: '증빙 요건 미비',
  grad_child: '자녀의 대학원 과정', tenure: '재직기간 미달', family_mismatch: '가족관계 불일치', wrong_document: '잘못된 서류', school_mismatch: '학교명 불일치',
  non_treatment: '치료 목적 아님', non_medical_institution: '비의료기관', date_mismatch: '수납일 불일치', under_minimum: '최소 금액 미만',
}
const QUALITY = { clean: '깨끗한 원본', light: '약한 스캔 잡음', heavy: '심한 저화질' }
const OUTCOME = { CORRECT: { label: '정답', cls: 'tag-ok' }, WRONG: { label: '오답', cls: 'tag-bad' }, REVIEW: { label: '담당자 확인', cls: 'tag-warn' } }

async function load() {
  try {
    const { data } = await api.get('/evaluations/latest')
    latest.value = data.latest
    running.value = data.running
    if (running.value && !timer) timer = setInterval(load, 3000)
    if (!running.value && timer) {
      clearInterval(timer)
      timer = null
    }
  } catch (e) {
    error.value = e.userMessage
  } finally {
    loading.value = false
  }
}
async function start() {
  error.value = null
  try {
    running.value = (await api.post('/evaluations')).data
    timer = timer || setInterval(load, 3000)
  } catch (e) {
    error.value = e.userMessage
  }
}
onMounted(load)
onBeforeUnmount(() => timer && clearInterval(timer))
const barColor = (acc) => (acc === null ? '#ccd3da' : acc >= 0.97 ? '#1a7f4b' : acc >= 0.85 ? '#c98a00' : '#c0392b')
</script>

<template>
  <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
    <h1 class="h5 fw-bold mb-0">검증 결과</h1>
    <span v-if="latest" class="small-muted">정답 세트 {{ s.total }}건 (승인 {{ s.expected.APPROVE }} · 반려 {{ s.expected.REJECT }}) · {{ dt(latest.finishedAt) }} · 결정 모델 {{ latest.provider }} / {{ latest.model }} · {{ s.elapsedSeconds }}초</span>
    <button class="btn btn-primary btn-sm ms-auto" :disabled="!!running" @click="start"><i class="bi bi-play-fill"></i> 정답 세트 검증 실행</button>
  </div>
  <div v-if="error" class="alert alert-danger py-2">{{ error }}</div>
  <div v-if="running" class="panel panel-body mb-3">
    <div class="d-flex mb-1"><b><span class="spinner-border spinner-border-sm me-2"></span>정답 세트를 실제 신청과 같은 흐름으로 검수하는 중…</b><span class="ms-auto">{{ running.progressDone }} / {{ running.progressTotal || '?' }}</span></div>
    <div class="progress" style="height: 8px"><div class="progress-bar" :style="{ width: (running.progressTotal ? (running.progressDone / running.progressTotal) * 100 : 3) + '%' }"></div></div>
  </div>
  <div v-if="loading" class="text-muted"><span class="spinner-border spinner-border-sm me-2"></span>불러오는 중…</div>
  <div v-else-if="!latest && !running" class="panel panel-body text-center text-muted py-5">아직 검증을 실행하지 않았습니다. ‘정답 세트 검증 실행’을 눌러 주세요.</div>

  <template v-if="latest && s">
    <div class="row g-3 mb-3">
      <div class="col-6 col-xl-3"><div class="kpi"><div class="label">① 항목 추출이 맞은 비율</div><div class="value">{{ pct(s.extraction.accuracy) }}</div>
        <div class="sub">{{ s.extraction.correct }} / {{ s.extraction.total }}개 항목 · 오독 {{ s.extraction.wrong }} · 읽지 못함 {{ s.extraction.unread }}</div></div></div>
      <div class="col-6 col-xl-3"><div class="kpi"><div class="label">② 최종 판정이 맞은 비율 (자동 판정분)</div><div class="value" :style="{ color: s.verdict.wrong ? 'var(--warn)' : 'var(--ok)' }">{{ pct(s.verdict.accuracyAuto) }}</div>
        <div class="sub">{{ s.verdict.correct }} / {{ s.verdict.correct + s.verdict.wrong }}건 · 잘못된 승인 {{ s.verdict.falseApprove }} · 잘못된 반려 {{ s.verdict.falseReject }}</div></div></div>
      <div class="col-6 col-xl-3"><div class="kpi"><div class="label">③ 조건 판정 정답률 (결정 모델)</div><div class="value">{{ pct(s.judgments.accuracy) }}</div>
        <div class="sub">{{ s.judgments.correct }} / {{ s.judgments.total }}개 조건 · ECE {{ cal?.ece ?? '-' }}</div></div></div>
      <div class="col-6 col-xl-3"><div class="kpi"><div class="label">④ 담당자 확인으로 넘어간 비율</div><div class="value">{{ pct(s.verdict.reviewRate) }}</div>
        <div class="sub">{{ s.verdict.review }} / {{ s.total }}건 · 자동 처리 {{ pct(s.verdict.autoRate) }} · 반려 사유 일치 {{ pct(s.reasonMatch.rate, 0) }}</div></div></div>
    </div>

    <div class="row g-3 mb-3">
      <div class="col-12 col-xl-6">
        <div class="panel h-100">
          <div class="panel-head"><i class="bi bi-bar-chart-steps"></i>확신도 구간별 실제 정답률 <span class="small-muted fw-normal ms-auto">결정 모델의 조건 판정 {{ cal?.count ?? 0 }}건</span></div>
          <div class="table-responsive">
            <table class="table table-sm mb-0">
              <thead><tr><th>확신도 구간</th><th class="text-end">건수</th><th class="text-end">평균 확신도</th><th>실제 정답률</th></tr></thead>
              <tbody>
                <tr v-for="b in cal?.bins || []" :key="b.range">
                  <td class="mono">{{ b.range }}</td><td class="text-end">{{ b.count }}</td><td class="text-end">{{ b.mean_confidence === null ? '—' : pct(b.mean_confidence) }}</td>
                  <td><div class="d-flex align-items-center gap-2"><div class="bar flex-grow-1"><div :style="{ width: (b.accuracy || 0) * 100 + '%', background: barColor(b.accuracy) }"></div></div>
                    <span class="mono" style="width: 3.6rem; text-align: right">{{ b.accuracy === null ? '—' : pct(b.accuracy) }}</span></div></td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="panel-body small-muted pt-2">확신도는 ‘내려진 충족·미충족 판정이 맞을 확률’입니다. 보정이 잘 된 모델이라면 구간의 평균 확신도와 실제 정답률이 비슷합니다. 건수가 적은 구간의 정답률은 참고용입니다.</div>
        </div>
      </div>
      <div class="col-12 col-xl-6">
        <div class="panel h-100">
          <div class="panel-head"><i class="bi bi-sliders"></i>담당자 확인으로 넘길 기준 <span class="small-muted fw-normal ms-auto">목표 정답률 {{ pct(s.targetAccuracy, 0) }}</span></div>
          <div class="table-responsive">
            <table class="table table-sm mb-0">
              <thead><tr><th>기준 확신도</th><th class="text-end">자동 판정 비율</th><th class="text-end">자동 판정분 정답률</th><th class="text-end">담당자 확인 비율</th></tr></thead>
              <tbody>
                <tr v-for="t in cal?.thresholds || []" :key="t.threshold" :class="{ 'table-success': s.policies.some((p) => p.autoThreshold === t.threshold) }">
                  <td class="mono">≥ {{ t.threshold.toFixed(2) }} <span v-if="s.policies.some((p) => p.autoThreshold === t.threshold)" class="tag tag-ok">현재 기준</span></td>
                  <td class="text-end">{{ pct(t.auto_rate) }}</td><td class="text-end">{{ t.auto_accuracy === null ? '—' : pct(t.auto_accuracy) }}</td><td class="text-end">{{ pct(t.review_rate) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="panel-body small pt-2">
            <template v-if="cal?.recommended?.met">목표 정답률을 만족하는 가장 낮은 기준은 <b>{{ cal.recommended.threshold.toFixed(2) }}</b>입니다(자동 판정 {{ pct(cal.recommended.auto_rate) }}, 정답률 {{ pct(cal.recommended.auto_accuracy) }}).</template>
            <template v-else>{{ cal?.recommended?.reason }}</template>
            <span class="small-muted d-block">기준은 규정 설정의 <span class="mono">decision.auto_threshold</span>로 정하며, 바꿀 때는 규정 관리에서 새 버전으로 올려 회귀 테스트 후 시행합니다. 결정 모델을 바꾸면 다시 검증해야 합니다.</span>
          </div>
        </div>
      </div>
    </div>

    <div class="row g-3 mb-3">
      <div class="col-12 col-lg-4">
        <div class="panel h-100">
          <div class="panel-head"><i class="bi bi-grid-3x3"></i>정답 ↔ 시스템 판정</div>
          <table class="table table-sm mb-0 text-center">
            <thead><tr><th class="text-start">정답＼판정</th><th>승인</th><th>반려</th><th>담당자 확인</th></tr></thead>
            <tbody>
              <tr v-for="k in ['APPROVE', 'REJECT']" :key="k">
                <th class="text-start">{{ VERDICT[k].label }}</th>
                <td v-for="v in ['APPROVE', 'REJECT', 'REVIEW']" :key="v" :class="k === v ? 'table-success fw-bold' : v !== 'REVIEW' && s.confusion[k][v] ? 'table-danger fw-bold' : ''">{{ s.confusion[k][v] }}</td>
              </tr>
            </tbody>
          </table>
          <div class="panel-body small-muted pt-2">잘못된 승인·반려(붉은 칸)가 없도록, 확실하지 않은 건은 담당자 확인으로 보냅니다.</div>
        </div>
      </div>
      <div class="col-12 col-lg-4">
        <div class="panel h-100">
          <div class="panel-head"><i class="bi bi-image"></i>증빙 품질별</div>
          <table class="table table-sm mb-0">
            <thead><tr><th>품질</th><th class="text-end">건수</th><th class="text-end">추출 정확도</th><th class="text-end">담당자 확인</th></tr></thead>
            <tbody>
              <tr v-for="(v, k) in s.byQuality" :key="k"><td>{{ QUALITY[k] || k }}</td><td class="text-end">{{ v.total }}</td>
                <td class="text-end">{{ pct(s.extraction.byQuality[k]?.accuracy) }}</td><td class="text-end">{{ v.review }}건</td></tr>
            </tbody>
          </table>
          <div class="panel-head border-top"><i class="bi bi-diagram-3"></i>지원 종류별</div>
          <table class="table table-sm mb-0">
            <thead><tr><th>지원 종류</th><th class="text-end">건수</th><th class="text-end">정답</th><th class="text-end">오답</th><th class="text-end">확인</th></tr></thead>
            <tbody>
              <tr v-for="p in s.policies" :key="p.supportType"><td>{{ p.name }} <span class="small-muted">v{{ p.version }}</span></td>
                <td class="text-end">{{ s.bySupportType[p.supportType]?.total }}</td><td class="text-end">{{ s.bySupportType[p.supportType]?.correct }}</td>
                <td class="text-end">{{ s.bySupportType[p.supportType]?.wrong }}</td><td class="text-end">{{ s.bySupportType[p.supportType]?.review }}</td></tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="col-12 col-lg-4">
        <div class="panel h-100">
          <div class="panel-head"><i class="bi bi-question-diamond"></i>조건별 판정 정답률</div>
          <table class="table table-sm mb-0">
            <thead><tr><th>조건</th><th class="text-end">정답 / 전체</th></tr></thead>
            <tbody><tr v-for="(v, k) in s.judgments.byJudgment" :key="k"><td>{{ v.label }} <span class="small-muted mono">{{ k }}</span></td><td class="text-end">{{ v.correct }} / {{ v.total }}</td></tr></tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="panel mb-3">
      <div class="panel-head"><i class="bi bi-list-ul"></i>유형별 결과</div>
      <div class="table-responsive">
        <table class="table table-sm mb-0">
          <thead><tr><th>유형</th><th class="text-end">건수</th><th class="text-end">정답</th><th class="text-end">오답</th><th class="text-end">담당자 확인</th></tr></thead>
          <tbody><tr v-for="(v, k) in s.byScenario" :key="k"><td>{{ SCENARIO[k] || k }}</td><td class="text-end">{{ v.total }}</td><td class="text-end">{{ v.correct }}</td>
            <td class="text-end" :class="{ 'text-danger fw-bold': v.wrong }">{{ v.wrong }}</td><td class="text-end">{{ v.review }}</td></tr></tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head"><i class="bi bi-card-checklist"></i>건별 결과
        <div class="btn-group btn-group-sm ms-auto">
          <button v-for="f in [['issues', '오답·확인·오독만'], ['all', '전체'], ['tuition', '학자금'], ['medical', '의료비']]" :key="f[0]" class="btn" :class="filter === f[0] ? 'btn-primary' : 'btn-outline-primary'" @click="filter = f[0]">{{ f[1] }}</button>
        </div>
      </div>
      <div class="table-responsive">
        <table class="table table-sm table-hover mb-0">
          <thead><tr><th>건</th><th>내용</th><th>파일</th><th>정답</th><th>시스템 판정</th><th>결과</th><th>확신도</th><th>사유</th></tr></thead>
          <tbody>
            <tr v-if="!cases.length"><td colspan="8" class="text-center text-muted py-3">해당하는 건이 없습니다.</td></tr>
            <template v-for="c in cases" :key="c.id">
              <tr class="clickable" @click="open[c.id] = !open[c.id]">
                <td class="mono">{{ c.id }}</td><td>{{ c.title }}</td><td class="small-muted">{{ c.format }} · {{ QUALITY[c.quality] }}</td>
                <td><span class="tag" :class="VERDICT[c.expected].cls">{{ VERDICT[c.expected].label }}</span></td>
                <td><span class="tag" :class="VERDICT[c.verdict].cls">{{ VERDICT[c.verdict].label }}</span></td>
                <td><span class="tag" :class="OUTCOME[c.outcome].cls">{{ OUTCOME[c.outcome].label }}</span></td>
                <td><ConfBar :value="c.confidence" /></td>
                <td class="small">{{ [...c.issueCodes, ...c.reviewReasons].slice(0, 3).join(', ') }}<span v-if="c.extractionMisses.length" class="tag tag-warn ms-1">추출 {{ c.extractionMisses.length }}</span></td>
              </tr>
              <tr v-if="open[c.id]">
                <td colspan="8" class="bg-light small">
                  <div class="row g-3 p-2">
                    <div class="col-12 col-lg-6">
                      <b>조건 판정</b>
                      <div v-for="j in c.judgments" :key="j.id">{{ j.label }}: {{ j.answerLabel ?? '—' }} · 확신도 {{ j.confidence ?? '—' }} · {{ j.decision }}
                        <span v-if="j.correct !== null" class="tag" :class="j.correct ? 'tag-ok' : 'tag-bad'">{{ j.correct ? '정답' : '오답' }} (정답: {{ j.truth }})</span></div>
                      <div v-if="!c.judgments.length" class="text-muted">없음</div>
                      <div class="mt-1"><b>기대 반려 사유</b> {{ (c.expectedReasons || []).join(', ') || '—' }} <span v-if="c.reasonMatched !== null" class="tag" :class="c.reasonMatched ? 'tag-ok' : 'tag-bad'">{{ c.reasonMatched ? '사유 일치' : '사유 불일치' }}</span></div>
                    </div>
                    <div class="col-12 col-lg-6">
                      <b>항목 추출</b> {{ c.extraction.correct }} / {{ c.extraction.total }}
                      <div v-for="(m, i) in c.extractionMisses" :key="i"><span class="tag" :class="m.kind === 'WRONG' ? 'tag-bad' : 'tag-warn'">{{ m.kind === 'WRONG' ? '오독' : '읽지 못함' }}</span>
                        {{ m.label }}: 정답 <span class="mono">{{ JSON.stringify(m.expected) }}</span> → <span class="mono">{{ JSON.stringify(m.got) }}</span> (신뢰도 {{ Number(m.confidence).toFixed(2) }})</div>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>
  </template>
</template>

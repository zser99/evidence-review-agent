<script setup>
// 검토 대기 목록: 담당자 확인이 필요한 건을 확신도가 낮은 순으로 모아 보여 준다.
import { onMounted, ref, watch } from 'vue'
import api from '../api'
import ConfBar from '../components/ConfBar.vue'
import { STATUS_CLS, VERDICT, dt, won } from '../format'

const tab = ref('review')
const rows = ref([])
const stats = ref({})
const loading = ref(true)
const error = ref(null)

const tabs = [
  { k: 'review', label: '담당자 확인 필요', icon: 'bi-person-check', stat: 'NEEDS_REVIEW' },
  { k: 'rejected', label: '반려 · 보완 필요', icon: 'bi-x-circle', stat: 'SUPPLEMENT_REQUIRED' },
  { k: 'processed', label: '처리 완료', icon: 'bi-check2-all' },
]

async function load() {
  loading.value = true
  error.value = null
  try {
    stats.value = (await api.get('/review/stats')).data
    rows.value =
      tab.value === 'processed'
        ? (await api.get('/applications', { params: { scope: 'all', status: 'AUTO_APPROVED,APPROVED,REJECTED' } })).data
        : (await api.get('/review/queue', { params: { kind: tab.value } })).data
  } catch (e) {
    error.value = e.userMessage
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(tab, load)
const n = (k) => stats.value[k] ?? 0
</script>

<template>
  <div class="d-flex align-items-center mb-3">
    <h1 class="h5 fw-bold mb-0">검토 대기</h1>
    <button class="btn btn-sm btn-light border ms-auto" @click="load"><i class="bi bi-arrow-clockwise"></i> 새로 고침</button>
  </div>
  <div class="row g-3 mb-3">
    <div class="col-6 col-lg-3"><div class="kpi"><div class="label">담당자 확인 필요</div><div class="value" style="color: var(--warn)">{{ n('NEEDS_REVIEW') }}</div><div class="sub">확신도가 낮거나 증빙을 읽지 못한 건</div></div></div>
    <div class="col-6 col-lg-3"><div class="kpi"><div class="label">반려 · 보완 필요</div><div class="value" style="color: var(--bad)">{{ n('SUPPLEMENT_REQUIRED') }}</div><div class="sub">구성원에게 보완 안내가 나간 건</div></div></div>
    <div class="col-6 col-lg-3"><div class="kpi"><div class="label">자동 승인</div><div class="value" style="color: var(--ok)">{{ n('AUTO_APPROVED') }}</div><div class="sub">모든 조건을 높은 확신도로 충족</div></div></div>
    <div class="col-6 col-lg-3"><div class="kpi"><div class="label">담당자 처리 완료</div><div class="value">{{ n('APPROVED') + n('REJECTED') }}</div><div class="sub">승인 {{ n('APPROVED') }} · 반려 {{ n('REJECTED') }}</div></div></div>
  </div>

  <ul class="nav nav-tabs mb-0">
    <li v-for="t in tabs" :key="t.k" class="nav-item">
      <a class="nav-link" :class="{ active: tab === t.k }" href="#" @click.prevent="tab = t.k"><i class="bi me-1" :class="t.icon"></i>{{ t.label }}
        <span v-if="t.stat" class="badge text-bg-secondary ms-1">{{ n(t.stat) }}</span></a>
    </li>
  </ul>
  <div v-if="error" class="alert alert-danger mt-3">{{ error }}</div>
  <div class="panel" style="border-top-left-radius: 0; border-top: 0">
    <div class="table-responsive">
      <table class="table table-hover mb-0">
        <thead>
          <tr>
            <th>신청번호</th><th>지원 종류</th><th>신청자</th><th>대상자</th><th class="text-end">신청 금액</th><th>자동 판정</th>
            <th>확신도 <i v-if="tab !== 'processed'" class="bi bi-sort-up" title="낮은 순"></i></th><th>{{ tab === 'processed' ? '상태' : '확인할 항목' }}</th><th>제출</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="9" class="text-center text-muted py-4"><span class="spinner-border spinner-border-sm me-2"></span>불러오는 중…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="9" class="text-center text-muted py-5">해당하는 건이 없습니다.</td></tr>
          <tr v-for="r in rows" :key="r.id" class="clickable" @click="$router.push(`/applications/${r.id}`)">
            <td class="mono">{{ r.applicationNo }}</td>
            <td>{{ r.supportName }}</td>
            <td>{{ r.applicantName }} <span class="small-muted d-block">{{ r.department }}</span></td>
            <td>{{ r.targetName || '-' }}</td>
            <td class="text-end">{{ won(r.requestedAmount) }}</td>
            <td><span v-if="r.autoVerdict" class="tag" :class="VERDICT[r.autoVerdict].cls">{{ VERDICT[r.autoVerdict].label }}</span></td>
            <td><ConfBar :value="r.confidence" /></td>
            <td>
              <span v-if="tab === 'processed'" class="tag" :class="STATUS_CLS[r.status]">{{ r.statusLabel }}</span>
              <template v-else>
                <span v-for="(x, i) in (r.reasons || []).slice(0, 4)" :key="i" class="tag me-1 mb-1" :class="x.severity === 'REJECT' ? 'tag-bad' : 'tag-warn'">{{ x.label }}</span>
                <span v-if="(r.reasons || []).length > 4" class="small-muted">+{{ r.reasons.length - 4 }}</span>
              </template>
            </td>
            <td class="small-muted text-nowrap">{{ dt(r.submittedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

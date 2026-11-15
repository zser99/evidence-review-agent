<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'
import { STATUS_CLS, dt, won } from '../format'

const rows = ref([])
const loading = ref(true)
const error = ref(null)

onMounted(async () => {
  try {
    rows.value = (await api.get('/applications', { params: { scope: 'mine' } })).data
  } catch (e) {
    error.value = e.userMessage
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="d-flex align-items-center mb-3">
    <h1 class="h5 fw-bold mb-0">내 신청</h1>
    <RouterLink class="btn btn-primary btn-sm ms-auto" to="/apply"><i class="bi bi-plus-lg"></i> 새 신청</RouterLink>
  </div>
  <div v-if="error" class="alert alert-danger">{{ error }}</div>
  <div class="panel">
    <div class="table-responsive">
      <table class="table table-hover mb-0">
        <thead>
          <tr><th>신청번호</th><th>지원 종류</th><th>대상자</th><th class="text-end">신청 금액</th><th>상태</th><th>작성·제출</th></tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="6" class="text-center text-muted py-4"><span class="spinner-border spinner-border-sm me-2"></span>불러오는 중…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="6" class="text-center text-muted py-5">아직 신청한 건이 없습니다. <RouterLink to="/apply">지원 신청하기</RouterLink></td></tr>
          <tr v-for="r in rows" :key="r.id" class="clickable" @click="$router.push(`/applications/${r.id}`)">
            <td class="mono">{{ r.applicationNo }}</td>
            <td>{{ r.supportName }}</td>
            <td>{{ r.targetName || '-' }}</td>
            <td class="text-end">{{ won(r.requestedAmount) }}</td>
            <td><span class="tag" :class="STATUS_CLS[r.status]">{{ r.statusLabel }}</span></td>
            <td class="small-muted">{{ dt(r.submittedAt || r.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

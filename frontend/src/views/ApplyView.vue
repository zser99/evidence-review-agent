<script setup>
// 신청 화면 1단계: 지원 종류를 고르고 신청 내용을 적는다. 저장하면 증빙을 올리는 화면으로 넘어간다.
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import FormFields from '../components/FormFields.vue'
import { useAuth } from '../stores/auth'

const router = useRouter()
const auth = useAuth()
const types = ref([])
const selected = ref(null)
const form = ref({})
const error = ref(null)
const busy = ref(false)
const loading = ref(true)

const def = computed(() => types.value.find((t) => t.supportType === selected.value))

onMounted(async () => {
  try {
    types.value = (await api.get('/support-types')).data
  } catch (e) {
    error.value = e.userMessage
  } finally {
    loading.value = false
  }
})

function pick(t) {
  selected.value = t.supportType
  form.value = {}
  error.value = null
}

async function next() {
  const missing = def.value.form.filter((f) => f.required && (form.value[f.key] === null || form.value[f.key] === undefined || form.value[f.key] === ''))
  if (missing.length) {
    error.value = '다음 항목을 입력해 주세요: ' + missing.map((f) => f.label).join(', ')
    return
  }
  busy.value = true
  error.value = null
  try {
    const { data } = await api.post('/applications', { supportType: selected.value, form: form.value })
    router.push(`/applications/${data.id}`)
  } catch (e) {
    error.value = e.userMessage
  } finally {
    busy.value = false
  }
}
function fillSelf() {
  form.value = { ...form.value, target_name: auth.user.name, relationship: '본인' }
}
</script>

<template>
  <h1 class="h5 fw-bold mb-3">지원 신청</h1>
  <div v-if="loading" class="text-muted"><span class="spinner-border spinner-border-sm me-2"></span>불러오는 중…</div>
  <template v-else>
    <div class="panel mb-3">
      <div class="panel-head"><span class="section-no">1</span>지원 종류 선택</div>
      <div class="panel-body">
        <div class="row g-3">
          <div v-for="t in types" :key="t.supportType" class="col-12 col-md-6 col-xl-4">
            <div class="type-card" :class="{ selected: selected === t.supportType }" role="button" tabindex="0" @click="pick(t)" @keyup.enter="pick(t)">
              <div class="d-flex align-items-center gap-2 mb-1">
                <i class="bi fs-4" :class="'bi-' + (t.icon || 'file-earmark-text')" style="color: var(--brand)"></i>
                <span class="fw-bold fs-6">{{ t.name }}</span>
                <span class="tag tag-mute ms-auto">규정 v{{ t.version }}</span>
              </div>
              <div class="small-muted">{{ t.description }}</div>
              <div class="small mt-2"><i class="bi bi-paperclip"></i> 필요한 서류: {{ t.documents.map((d) => d.name + (d.required ? '' : '(해당 시)')).join(', ') }}</div>
            </div>
          </div>
          <div v-if="!types.length" class="col-12 text-muted">시행 중인 지원 종류가 없습니다.</div>
        </div>
      </div>
    </div>

    <div v-if="def" class="panel mb-3">
      <div class="panel-head">
        <span class="section-no">2</span>신청 내용
        <button type="button" class="btn btn-sm btn-outline-secondary ms-auto" @click="fillSelf">본인 정보로 채우기</button>
      </div>
      <div class="panel-body">
        <FormFields v-model="form" :fields="def.form" />
        <div v-if="error" class="alert alert-danger py-2 mt-3 mb-0">{{ error }}</div>
      </div>
    </div>
    <div v-if="def" class="d-flex justify-content-end">
      <button class="btn btn-primary" :disabled="busy" @click="next">
        <span v-if="busy" class="spinner-border spinner-border-sm me-1"></span>저장하고 증빙 올리기 <i class="bi bi-arrow-right"></i>
      </button>
    </div>
  </template>
</template>

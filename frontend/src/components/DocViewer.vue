<script setup>
// 증빙 원본 미리보기 + 추출 위치 표시. 이미지는 인증 헤더가 필요해 blob으로 받아 온다.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchBlobUrl } from '../api'

const props = defineProps({
  appId: { type: [Number, String], required: true },
  file: { type: Object, default: null }, // {id, pages:[{index,width,height}], fields:[{key,bbox,page,status}]}
  activeKey: { type: String, default: null },
})
const emit = defineEmits(['pick'])

const page = ref(0)
const url = ref(null)
const loading = ref(false)
const error = ref(null)

const meta = computed(() => props.file?.pages?.[page.value] || null)
const boxes = computed(() => (props.file?.fields || []).filter((f) => f.bbox && (f.page ?? 0) === page.value))

async function load() {
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = null
  error.value = null
  if (!props.file) return
  loading.value = true
  try {
    url.value = await fetchBlobUrl(`/applications/${props.appId}/evidence/${props.file.id}/pages/${page.value}`)
  } catch (e) {
    error.value = e.userMessage || '미리보기를 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

watch(
  () => props.file?.id,
  () => {
    page.value = 0
    load()
  },
  { immediate: true },
)
watch(page, load)
watch(
  () => props.activeKey,
  (k) => {
    const f = (props.file?.fields || []).find((x) => x.key === k)
    if (f && f.page !== null && f.page !== undefined && f.page !== page.value) page.value = f.page
  },
)
onBeforeUnmount(() => url.value && URL.revokeObjectURL(url.value))

async function openOriginal() {
  const u = await fetchBlobUrl(`/applications/${props.appId}/evidence/${props.file.id}/original`)
  window.open(u, '_blank', 'noopener')
}
const color = (s) => (s === 'OK' ? '#1a7f4b' : '#c98a00')
</script>

<template>
  <div v-if="!file" class="dropzone py-5"><i class="bi bi-file-earmark-image fs-3 d-block mb-1"></i>증빙을 올리면 원본과 추출 위치가 여기에 표시됩니다.</div>
  <div v-else>
    <div class="d-flex align-items-center gap-2 mb-2 small">
      <span class="text-truncate fw-semibold" :title="file.filename"><i class="bi bi-paperclip"></i> {{ file.filename }}</span>
      <span class="tag tag-mute" :title="'문서 전체 평균 인식 신뢰도'">인식 품질 {{ ((file.quality || 0) * 100).toFixed(0) }}%</span>
      <span v-if="file.lowQuality" class="tag tag-warn">저화질</span>
      <span class="ms-auto d-flex gap-1">
        <template v-if="file.pages.length > 1">
          <button class="btn btn-sm btn-outline-secondary py-0" :disabled="page === 0" @click="page--">‹</button>
          <span class="align-self-center">{{ page + 1 }} / {{ file.pages.length }}</span>
          <button class="btn btn-sm btn-outline-secondary py-0" :disabled="page >= file.pages.length - 1" @click="page++">›</button>
        </template>
        <button class="btn btn-sm btn-outline-secondary py-0" title="원본 파일 열기" @click="openOriginal"><i class="bi bi-box-arrow-up-right"></i> 원본</button>
      </span>
    </div>
    <div class="docview">
      <div v-if="loading" class="p-5 text-center text-muted"><span class="spinner-border spinner-border-sm me-2"></span>불러오는 중…</div>
      <div v-else-if="error" class="p-4 text-center text-danger">{{ error }}</div>
      <div v-else-if="url && meta" class="docpage">
        <img :src="url" alt="증빙 원본 미리보기" />
        <svg :viewBox="`0 0 ${meta.width} ${meta.height}`" preserveAspectRatio="none">
          <rect
            v-for="b in boxes"
            :key="b.key"
            :x="b.bbox[0] - 4"
            :y="b.bbox[1] - 4"
            :width="b.bbox[2] - b.bbox[0] + 8"
            :height="b.bbox[3] - b.bbox[1] + 8"
            rx="4"
            :fill="b.key === activeKey ? 'rgba(255,193,7,0.35)' : 'rgba(31,78,121,0.06)'"
            :stroke="b.key === activeKey ? '#e0a100' : color(b.status)"
            :stroke-width="b.key === activeKey ? 4 : 1.5"
            style="pointer-events: all; cursor: pointer"
            @click="emit('pick', b.key)"
          >
            <title>{{ b.label }}: {{ b.display }}</title>
          </rect>
        </svg>
      </div>
    </div>
  </div>
</template>

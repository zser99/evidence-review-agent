<script setup>
// 지원 종류 설정(application_form)대로 신청서 입력란을 그린다. 지원 종류가 늘어도 화면 코드는 그대로다.
import { computed } from 'vue'

const props = defineProps({
  fields: { type: Array, required: true },
  modelValue: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const year = new Date().getFullYear()
const semesters = computed(() => [year - 1, year, year + 1].flatMap((y) => [1, 2].map((t) => ({ v: `${y}-${t}`, l: `${y}학년도 ${t}학기` }))))

function set(key, value) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
const money = (v) => (v === null || v === undefined || v === '' ? '' : Number(v).toLocaleString('ko-KR'))
function setMoney(key, text) {
  const digits = String(text).replace(/[^0-9]/g, '')
  set(key, digits === '' ? null : Number(digits))
}
</script>

<template>
  <div class="row g-3">
    <div v-for="f in fields" :key="f.key" class="col-12 col-md-6">
      <label class="form-label mb-1" :for="'f-' + f.key">{{ f.label }} <span v-if="f.required" class="text-danger">*</span></label>
      <select v-if="f.type === 'enum'" :id="'f-' + f.key" class="form-select" :disabled="disabled" :value="modelValue[f.key] ?? ''" @change="set(f.key, $event.target.value || null)">
        <option value="">선택</option>
        <option v-for="o in f.options" :key="o" :value="o">{{ o }}</option>
      </select>
      <select v-else-if="f.type === 'semester'" :id="'f-' + f.key" class="form-select" :disabled="disabled" :value="modelValue[f.key] ?? ''" @change="set(f.key, $event.target.value || null)">
        <option value="">선택</option>
        <option v-for="s in semesters" :key="s.v" :value="s.v">{{ s.l }}</option>
      </select>
      <input v-else-if="f.type === 'date'" :id="'f-' + f.key" type="date" class="form-control" :disabled="disabled" :value="modelValue[f.key] ?? ''" @input="set(f.key, $event.target.value || null)" />
      <div v-else-if="f.type === 'money'" class="input-group">
        <input :id="'f-' + f.key" class="form-control text-end" inputmode="numeric" :disabled="disabled" :value="money(modelValue[f.key])" @input="setMoney(f.key, $event.target.value)" />
        <span class="input-group-text">원</span>
      </div>
      <input v-else :id="'f-' + f.key" class="form-control" :disabled="disabled" :value="modelValue[f.key] ?? ''" @input="set(f.key, $event.target.value)" />
      <div v-if="f.help" class="form-text">{{ f.help }}</div>
    </div>
  </div>
</template>

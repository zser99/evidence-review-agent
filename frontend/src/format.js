export const won = (v) => (v === null || v === undefined || v === '' ? '-' : Number(v).toLocaleString('ko-KR') + '원')
export const pct = (v, digits = 1) => (v === null || v === undefined ? '-' : (v * 100).toFixed(digits) + '%')
export const dt = (iso) => (iso ? new Date(iso).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '-')
export const day = (iso) => (iso ? new Date(iso).toLocaleDateString('ko-KR') : '-')

export const VERDICT = {
  APPROVE: { label: '승인', cls: 'tag-ok', icon: 'bi-check-circle-fill' },
  REJECT: { label: '반려', cls: 'tag-bad', icon: 'bi-x-circle-fill' },
  REVIEW: { label: '담당자 확인 필요', cls: 'tag-warn', icon: 'bi-person-check-fill' },
}

export const STATUS_CLS = {
  DRAFT: 'tag-mute',
  AUTO_APPROVED: 'tag-ok',
  APPROVED: 'tag-ok',
  SUPPLEMENT_REQUIRED: 'tag-bad',
  REJECTED: 'tag-bad',
  NEEDS_REVIEW: 'tag-warn',
}

// 대조·조건 판정 결과의 표시
export const RESULT = {
  MATCH: { label: '일치', cls: 'tag-ok' },
  MISMATCH: { label: '불일치', cls: 'tag-bad' },
  UNREADABLE: { label: '읽지 못함', cls: 'tag-warn' },
  NO_DOCUMENT: { label: '서류 없음', cls: 'tag-mute' },
  PASS: { label: '충족', cls: 'tag-ok' },
  FAIL: { label: '미충족', cls: 'tag-bad' },
  REVIEW: { label: '확인 필요', cls: 'tag-warn' },
  UNKNOWN: { label: '판단 불가', cls: 'tag-warn' },
  SKIPPED: { label: '해당 없음', cls: 'tag-mute' },
  OK: { label: '읽음', cls: 'tag-ok' },
  LOW_CONFIDENCE: { label: '읽지 못함(신뢰도 낮음)', cls: 'tag-warn' },
  NOT_FOUND: { label: '찾지 못함', cls: 'tag-mute' },
}

export const confColor = (v, threshold = 0.85) => (v >= threshold ? '#1a7f4b' : v >= 0.6 ? '#c98a00' : '#c0392b')

export const AUDIT = {
  APPLICATION_CREATED: '신청서 작성',
  EVIDENCE_UPLOADED: '증빙 업로드',
  EVIDENCE_REMOVED: '증빙 삭제',
  EVIDENCE_ORIGINAL_VIEWED: '증빙 원본 열람',
  REVIEW_COMPLETED: '자동 검수 완료',
  HR_DECISION: '담당자 결정',
}

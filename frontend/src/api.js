import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 180000 })

api.interceptors.request.use((cfg) => {
  const token = sessionStorage.getItem('token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

api.interceptors.response.use(
  (r) => r,
  (err) => {
    const status = err.response?.status
    if (status === 401 && !err.config?.url?.includes('/auth/login')) {
      sessionStorage.removeItem('token')
      sessionStorage.removeItem('user')
      if (location.pathname !== '/login') location.assign('/login')
    }
    const data = err.response?.data
    err.userMessage = (data && data.message) || (status ? `요청을 처리하지 못했습니다 (HTTP ${status}).` : '서버에 연결할 수 없습니다.')
    return Promise.reject(err)
  },
)

export default api

/** 인증이 필요한 이미지·파일을 받아 브라우저에서 쓸 수 있는 object URL로 바꾼다. */
export async function fetchBlobUrl(path) {
  const r = await api.get(path, { responseType: 'blob' })
  return URL.createObjectURL(r.data)
}

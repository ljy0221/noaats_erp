import { api } from './client'

/**
 * Spring Security formLogin — application/x-www-form-urlencoded.
 * 성공 시 200 + JSESSIONID 쿠키. 실패 시 401.
 * CSRF는 /login 경로에서 ignoringRequestMatchers로 제외되어 있어 토큰 불필요.
 */
export async function login(username: string, password: string): Promise<void> {
  const body = new URLSearchParams()
  body.append('username', username)
  body.append('password', password)

  await api.post('/login', body, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  })
}

/**
 * 로그인 후 후속 상태 변경 요청을 위해 CSRF 토큰 쿠키를 미리 받아둔다.
 * CookieCsrfTokenRepository는 "토큰을 조회하는 순간" 쿠키로 내려주므로
 * 아무 인증된 GET 요청 하나로 충분하다.
 */
export async function primeCsrf(): Promise<void> {
  try {
    await api.get('/api/monitor/dashboard', { validateStatus: () => true })
  } catch {
    // 예외 무시 — 토큰 획득이 목적
  }
}

export async function logout(): Promise<void> {
  await api.post('/logout')
}

export interface MeResponse {
  authenticated: boolean
}

/**
 * 현재 세션 인증 여부 확인용 — 보호된 엔드포인트 프로빙.
 * 성공 시 authenticated=true, 401 시 false.
 */
export async function probeAuthenticated(): Promise<boolean> {
  try {
    await api.get('/api/monitor/dashboard')
    return true
  } catch {
    return false
  }
}

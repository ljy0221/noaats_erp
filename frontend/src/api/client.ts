import axios, { AxiosError, type AxiosInstance, type AxiosResponse } from 'axios'
import { ApiError, type ApiErrorData, type ApiResponse, type ErrorCode } from './types'

const UNAUTHENTICATED_EVENT = 'ifms:unauthenticated'

export const unauthenticatedBus = new EventTarget()

function isApiResponse(body: unknown): body is ApiResponse<unknown> {
  return (
    typeof body === 'object' &&
    body !== null &&
    'success' in body &&
    'timestamp' in body
  )
}

export function createApiClient(): AxiosInstance {
  const instance = axios.create({
    baseURL: '/',
    withCredentials: true,
    xsrfCookieName: 'XSRF-TOKEN',
    xsrfHeaderName: 'X-XSRF-TOKEN',
    timeout: 10000,
    headers: {
      Accept: 'application/json',
    },
  })

  instance.interceptors.response.use(
    (response: AxiosResponse) => {
      const body = response.data
      if (isApiResponse(body)) {
        if (body.success) {
          return { ...response, data: body.data }
        }
        const errorData = (body.data ?? null) as ApiErrorData | null
        throw new ApiError(
          response.status,
          errorData?.errorCode ?? null,
          errorData,
          body.message ?? '요청 실패',
        )
      }
      return response
    },
    (error: AxiosError) => {
      if (error.response) {
        const status = error.response.status
        const body = error.response.data
        let code: ErrorCode | null = null
        let data: ApiErrorData | null = null
        let message = error.message

        if (isApiResponse(body)) {
          data = (body.data ?? null) as ApiErrorData | null
          code = data?.errorCode ?? null
          message = body.message ?? message
        }

        if (status === 401) {
          unauthenticatedBus.dispatchEvent(new Event(UNAUTHENTICATED_EVENT))
        }

        return Promise.reject(new ApiError(status, code, data, message))
      }
      return Promise.reject(new ApiError(0, null, null, error.message || '네트워크 오류'))
    },
  )

  return instance
}

export const api = createApiClient()
export const UNAUTH_EVENT = UNAUTHENTICATED_EVENT

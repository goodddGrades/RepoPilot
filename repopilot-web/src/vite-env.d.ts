/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端 API 前缀，默认 /api（由 Vite 代理转发） */
  readonly VITE_API_BASE_URL?: string
  /** 开发环境代理目标，默认 http://127.0.0.1:8080 */
  readonly VITE_API_PROXY_TARGET?: string
  /** 前端开发服务器端口，默认 5173 */
  readonly VITE_DEV_PORT?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

import { getJson, patchJson, postJson, putJson } from '@/api/http'
import { buildQuery } from '@/api/query'
import type { AgentType, AgentVersionView, AgentView, PageResponse, RunnerType } from '@/types/domain'

export interface AgentListParams {
  page?: number
  pageSize?: number
  status?: string
  keyword?: string
}

export interface CreateAgentPayload {
  code: string
  name: string
  description?: string
  agentType: AgentType
}

export interface CreateAgentVersionPayload {
  runnerType: RunnerType
  config: unknown
}

export function listAgents(params: AgentListParams = {}): Promise<PageResponse<AgentView>> {
  return getJson<PageResponse<AgentView>>(`/v1/agents${buildQuery({ ...params })}`)
}

export function getAgent(id: string): Promise<AgentView> {
  return getJson<AgentView>(`/v1/agents/${id}`)
}

export function createAgent(payload: CreateAgentPayload): Promise<AgentView> {
  return postJson<AgentView>('/v1/agents', payload)
}

export function patchAgent(id: string, payload: Partial<CreateAgentPayload> & { status?: string }) {
  return patchJson<AgentView>(`/v1/agents/${id}`, payload)
}

export function listAgentVersions(
  agentId: string,
  params: { page?: number; pageSize?: number } = {},
): Promise<PageResponse<AgentVersionView>> {
  return getJson<PageResponse<AgentVersionView>>(
    `/v1/agents/${agentId}/versions${buildQuery({ ...params })}`,
  )
}

export function createAgentVersion(
  agentId: string,
  payload: CreateAgentVersionPayload,
): Promise<AgentVersionView> {
  return postJson<AgentVersionView>(`/v1/agents/${agentId}/versions`, payload)
}

export function updateAgentVersion(
  versionId: string,
  payload: CreateAgentVersionPayload,
): Promise<AgentVersionView> {
  return putJson<AgentVersionView>(`/v1/agent-versions/${versionId}`, payload)
}

export function publishAgentVersion(versionId: string): Promise<AgentVersionView> {
  return postJson<AgentVersionView>(`/v1/agent-versions/${versionId}/publish`)
}

export function archiveAgentVersion(versionId: string): Promise<AgentVersionView> {
  return postJson<AgentVersionView>(`/v1/agent-versions/${versionId}/archive`)
}

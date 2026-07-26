import { http, unsafeRequest } from '@/app/http'
import type {
  CreatedTask,
  CreateTaskCommand,
  OperatorOption,
  TaskDetail,
  TaskPage,
  TaskSearch,
} from './task.types'

export async function createTask(
  command: CreateTaskCommand,
): Promise<CreatedTask> {
  const response = await unsafeRequest<CreatedTask>({
    method: 'post',
    url: '/tasks',
    data: command,
  })
  return response.data
}

export async function searchTasks(search: TaskSearch): Promise<TaskPage> {
  const response = await http.get<TaskPage>('/tasks', { params: search })
  return response.data
}

export async function taskDetail(id: string): Promise<TaskDetail> {
  const response = await http.get<TaskDetail>(`/tasks/${id}`)
  return response.data
}

export async function suggestSystemNames(query: string): Promise<string[]> {
  if (query.trim().length < 2) {
    return []
  }
  const response = await http.get<string[]>('/tasks/system-names', {
    params: { query: query.trim(), limit: 10 },
  })
  return response.data
}

export async function callTask(id: string): Promise<void> {
  await unsafeRequest({
    method: 'post',
    url: `/tasks/${id}/call`,
  })
}

export async function completeTask(
  id: string,
  actualMinutes: number,
): Promise<void> {
  await unsafeRequest({
    method: 'post',
    url: `/tasks/${id}/complete`,
    data: { actualMinutes },
  })
}

export async function transferTask(
  id: string,
  targetId: string,
  reason: string,
): Promise<void> {
  await unsafeRequest({
    method: 'post',
    url: `/assignments/tasks/${id}/transfer`,
    data: { targetId, reason },
  })
}

export async function listOperators(
  operationDate: string,
): Promise<OperatorOption[]> {
  const response = await http.get<OperatorOption[]>('/assignments/operators', {
    params: { operationDate },
  })
  return response.data
}

export function notifyTaskChanged(): void {
  window.dispatchEvent(new CustomEvent('ops-task-changed'))
}

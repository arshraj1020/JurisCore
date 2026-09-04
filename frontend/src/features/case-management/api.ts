import { api } from '@/lib/api/client';
import type {
  Court, CourtRequest, CreateHearingRequest, CreateReminderRequest, CreateTaskRequest,
  Deadline, DeadlineRequest, DeadlineStatus, Hearing, HearingStatus, PageResponse,
  Reminder, Task, TaskStatus,
} from '@/types/api';

export const courtsApi = {
  list: (params: { includeRetired?: boolean; page?: number; size?: number }) =>
    api.get<PageResponse<Court>>('/api/v1/courts', { ...params }),
  create: (body: CourtRequest) => api.post<Court>('/api/v1/courts', body),
  update: (courtId: string, body: CourtRequest) =>
    api.put<Court>(`/api/v1/courts/${courtId}`, body),
  retire: (courtId: string) => api.delete<Court>(`/api/v1/courts/${courtId}`),
};

export const hearingsApi = {
  list: (params: {
    caseId?: string; courtId?: string; status?: string;
    from?: string; to?: string; page?: number; size?: number;
  }) => api.get<PageResponse<Hearing>>('/api/v1/hearings', { ...params }),
  schedule: (body: CreateHearingRequest) => api.post<Hearing>('/api/v1/hearings', body),
  changeStatus: (hearingId: string, status: HearingStatus, outcome?: string) =>
    api.patch<Hearing>(`/api/v1/hearings/${hearingId}/status`, { status, outcome }),
};

export const tasksApi = {
  listForCase: (caseId: string, params: { status?: string; page?: number; size?: number }) =>
    api.get<PageResponse<Task>>(`/api/v1/cases/${caseId}/tasks`, { ...params }),
  create: (caseId: string, body: CreateTaskRequest) =>
    api.post<Task>(`/api/v1/cases/${caseId}/tasks`, body),
  changeStatus: (taskId: string, status: TaskStatus) =>
    api.patch<Task>(`/api/v1/tasks/${taskId}/status`, { status }),
  remove: (taskId: string) => api.delete<Task>(`/api/v1/tasks/${taskId}`),
};

export const deadlinesApi = {
  listForCase: (caseId: string, params: { status?: string; page?: number; size?: number }) =>
    api.get<PageResponse<Deadline>>(`/api/v1/cases/${caseId}/deadlines`, { ...params }),
  create: (caseId: string, body: DeadlineRequest) =>
    api.post<Deadline>(`/api/v1/cases/${caseId}/deadlines`, body),
  changeStatus: (deadlineId: string, status: DeadlineStatus) =>
    api.patch<Deadline>(`/api/v1/deadlines/${deadlineId}/status`, { status }),
  remove: (deadlineId: string) => api.delete<Deadline>(`/api/v1/deadlines/${deadlineId}`),
};

export const remindersApi = {
  list: (params: { status?: string; page?: number; size?: number }) =>
    api.get<PageResponse<Reminder>>('/api/v1/reminders', { ...params }),
  forTask: (taskId: string, body: CreateReminderRequest) =>
    api.post<Reminder>(`/api/v1/tasks/${taskId}/reminders`, body),
  forDeadline: (deadlineId: string, body: CreateReminderRequest) =>
    api.post<Reminder>(`/api/v1/deadlines/${deadlineId}/reminders`, body),
  cancel: (reminderId: string) => api.delete<Reminder>(`/api/v1/reminders/${reminderId}`),
};

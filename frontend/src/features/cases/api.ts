import { api } from '@/lib/api/client';
import type {
  CaseAssignment, CaseEvent, CaseStatus, CreateCaseRequest, LegalCase, PageResponse,
  UpdateCaseRequest,
} from '@/types/api';

export interface CaseListParams {
  search?: string; status?: string; clientId?: string; page?: number; size?: number;
}

export const casesApi = {
  list: (params: CaseListParams) =>
    api.get<PageResponse<LegalCase>>('/api/v1/cases', { ...params }),
  byId: (caseId: string) => api.get<LegalCase>(`/api/v1/cases/${caseId}`),
  create: (body: CreateCaseRequest) => api.post<LegalCase>('/api/v1/cases', body),
  update: (caseId: string, body: UpdateCaseRequest) =>
    api.put<LegalCase>(`/api/v1/cases/${caseId}`, body),
  changeStatus: (caseId: string, status: CaseStatus) =>
    api.patch<LegalCase>(`/api/v1/cases/${caseId}/status`, { status }),

  timeline: (caseId: string, page: number) =>
    api.get<PageResponse<CaseEvent>>(`/api/v1/cases/${caseId}/timeline`, { page, size: 20 }),
  addNote: (caseId: string, summary: string) =>
    api.post<CaseEvent>(`/api/v1/cases/${caseId}/timeline`, { summary }),

  assignments: (caseId: string) =>
    api.get<CaseAssignment[]>(`/api/v1/cases/${caseId}/assignments`),
  assign: (caseId: string, lawyerUserId: string, lead: boolean) =>
    api.post<CaseAssignment>(`/api/v1/cases/${caseId}/assignments`, { lawyerUserId, lead }),
  unassign: (caseId: string, lawyerUserId: string, newLeadUserId?: string) =>
    api.delete<void>(`/api/v1/cases/${caseId}/assignments/${lawyerUserId}`,
      newLeadUserId ? { newLeadUserId } : undefined),
};

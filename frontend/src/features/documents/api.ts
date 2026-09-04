import { api } from '@/lib/api/client';
import type {
  CaseDocument, CreateDocumentRequest, DocumentDownloadTicket, DocumentUploadTicket,
  PageResponse,
} from '@/types/api';

export const documentsApi = {
  listForCase: (caseId: string, params: { status?: string; page?: number; size?: number }) =>
    api.get<PageResponse<CaseDocument>>(`/api/v1/cases/${caseId}/documents`, { ...params }),
  register: (caseId: string, body: CreateDocumentRequest) =>
    api.post<DocumentUploadTicket>(`/api/v1/cases/${caseId}/documents`, body),
  complete: (documentId: string) =>
    api.post<CaseDocument>(`/api/v1/documents/${documentId}/complete`),
  download: (documentId: string) =>
    api.get<DocumentDownloadTicket>(`/api/v1/documents/${documentId}/download`),
  rename: (documentId: string, filename: string, description: string | null, version: number) =>
    api.put<CaseDocument>(`/api/v1/documents/${documentId}`, { filename, description, version }),
  remove: (documentId: string) => api.delete<CaseDocument>(`/api/v1/documents/${documentId}`),
};

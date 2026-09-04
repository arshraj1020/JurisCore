import { api } from '@/lib/api/client';
import type { Client, ClientRequest, PageResponse } from '@/types/api';

export interface ClientListParams { search?: string; page?: number; size?: number }

export const clientsApi = {
  list: (params: ClientListParams) =>
    api.get<PageResponse<Client>>('/api/v1/clients', { ...params }),
  byId: (clientId: string) => api.get<Client>(`/api/v1/clients/${clientId}`),
  create: (body: ClientRequest) => api.post<Client>('/api/v1/clients', body),
  update: (clientId: string, body: ClientRequest) =>
    api.put<Client>(`/api/v1/clients/${clientId}`, body),
  remove: (clientId: string) => api.delete<Client>(`/api/v1/clients/${clientId}`),
};

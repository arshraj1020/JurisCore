import { api } from '@/lib/api/client';
import type {
  BillingProfile, CancelInvoiceRequest, CreateInvoiceRequest, Invoice, IssueInvoiceRequest,
  PageResponse, Payment, RecordPaymentRequest, UpdateInvoiceRequest,
} from '@/types/api';

export interface InvoiceListParams {
  status?: string; clientId?: string; caseId?: string; page?: number; size?: number;
}

export const invoicesApi = {
  list: (params: InvoiceListParams) =>
    api.get<PageResponse<Invoice>>('/api/v1/invoices', { ...params }),
  byId: (invoiceId: string) => api.get<Invoice>(`/api/v1/invoices/${invoiceId}`),
  create: (body: CreateInvoiceRequest) => api.post<Invoice>('/api/v1/invoices', body),
  update: (invoiceId: string, body: UpdateInvoiceRequest) =>
    api.patch<Invoice>(`/api/v1/invoices/${invoiceId}`, body),
  issue: (invoiceId: string, body: IssueInvoiceRequest) =>
    api.post<Invoice>(`/api/v1/invoices/${invoiceId}/issue`, body),
  cancel: (invoiceId: string, body: CancelInvoiceRequest) =>
    api.post<Invoice>(`/api/v1/invoices/${invoiceId}/cancel`, body),
  payments: (invoiceId: string, page: number) =>
    api.get<PageResponse<Payment>>(`/api/v1/invoices/${invoiceId}/payments`, { page, size: 20 }),
  recordPayment: (invoiceId: string, body: RecordPaymentRequest) =>
    api.post<Payment>(`/api/v1/invoices/${invoiceId}/payments`, body),
};

export const billingProfileApi = {
  current: () => api.get<BillingProfile>('/api/v1/billing/profile'),
  update: (body: Partial<BillingProfile>) =>
    api.patch<BillingProfile>('/api/v1/billing/profile', body),
};

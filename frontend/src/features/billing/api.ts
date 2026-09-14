import { api, downloadFile } from '@/lib/api/client';
import type {
  BillingProfile, CancelInvoiceRequest, CreateInvoiceRequest, Invoice, IssueInvoiceRequest,
  InvoiceEmailSent, PageResponse, Payment, RecordPaymentRequest,
  UpdateBillingProfileRequest, UpdateInvoiceRequest,
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
  /**
   * Rendered fresh by the server on every call, in the firm's own identity — see
   * `InvoicePdfService`. The fallback name is only used if the response somehow arrives
   * without a `Content-Disposition` header; the server always sends one.
   */
  downloadPdf: (invoiceId: string, invoiceNumber: string) =>
    downloadFile(`/api/v1/invoices/${invoiceId}/pdf`, `invoice-${invoiceNumber}.pdf`),
  /**
   * Sends the same PDF to the address on the client's record. No body: the recipient is
   * the client the invoice bills, and the server will not accept one from here.
   */
  email: (invoiceId: string) =>
    api.post<InvoiceEmailSent>(`/api/v1/invoices/${invoiceId}/email`),
};

export const billingProfileApi = {
  current: () => api.get<BillingProfile>('/api/v1/billing/profile'),
  /**
   * A full replacement, not a patch — see `UpdateBillingProfileRequest`. The type is not
   * `Partial<...>` on purpose: the endpoint nulls every field the body omits.
   */
  update: (body: UpdateBillingProfileRequest) =>
    api.patch<BillingProfile>('/api/v1/billing/profile', body),
};

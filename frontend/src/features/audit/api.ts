import { api } from '@/lib/api/client';
import type { AuditEvent, PageResponse } from '@/types/api';

export interface AuditListParams {
  actor?: string; action?: string; entityType?: string; entityId?: string;
  from?: string; to?: string; page?: number; size?: number;
}

/**
 * Reads only. The backend exposes no write, update or delete for audit records, so
 * neither does this module — the absence is the contract, not an omission.
 */
export const auditApi = {
  list: (params: AuditListParams) =>
    api.get<PageResponse<AuditEvent>>('/api/v1/audit', { ...params }),
};

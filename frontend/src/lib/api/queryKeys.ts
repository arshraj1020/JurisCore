/**
 * Every query key in one file.
 *
 * Cache invalidation is the part of a client that rots first: a mutation invalidates
 * `['cases']` while a list reads `['case-list']`, the screen does not refresh, and
 * somebody adds a manual refetch instead of finding the mismatch. Keys built from one
 * hierarchy make `invalidateQueries({ queryKey: keys.cases.all })` reach every case query
 * by construction.
 */
export const keys = {
  me: ['me'] as const,
  organization: ['organization'] as const,

  users: {
    all: ['users'] as const,
    list: (params: unknown) => ['users', 'list', params] as const,
  },
  clients: {
    all: ['clients'] as const,
    list: (params: unknown) => ['clients', 'list', params] as const,
    detail: (id: string) => ['clients', 'detail', id] as const,
  },
  cases: {
    all: ['cases'] as const,
    list: (params: unknown) => ['cases', 'list', params] as const,
    detail: (id: string) => ['cases', 'detail', id] as const,
    timeline: (id: string, page: number) => ['cases', id, 'timeline', page] as const,
    assignments: (id: string) => ['cases', id, 'assignments'] as const,
  },
  courts: {
    all: ['courts'] as const,
    list: (params: unknown) => ['courts', 'list', params] as const,
  },
  hearings: {
    all: ['hearings'] as const,
    list: (params: unknown) => ['hearings', 'list', params] as const,
  },
  tasks: {
    all: ['tasks'] as const,
    forCase: (caseId: string, params: unknown) => ['tasks', caseId, params] as const,
  },
  deadlines: {
    all: ['deadlines'] as const,
    forCase: (caseId: string, params: unknown) => ['deadlines', caseId, params] as const,
  },
  reminders: {
    all: ['reminders'] as const,
    list: (params: unknown) => ['reminders', 'list', params] as const,
  },
  documents: {
    all: ['documents'] as const,
    forCase: (caseId: string, params: unknown) => ['documents', caseId, params] as const,
    detail: (id: string) => ['documents', 'detail', id] as const,
  },
  invoices: {
    all: ['invoices'] as const,
    list: (params: unknown) => ['invoices', 'list', params] as const,
    detail: (id: string) => ['invoices', 'detail', id] as const,
    payments: (id: string, page: number) => ['invoices', id, 'payments', page] as const,
  },
  billingProfile: ['billing-profile'] as const,
  notifications: {
    all: ['notifications'] as const,
    list: (params: unknown) => ['notifications', 'list', params] as const,
    unreadCount: ['notifications', 'unread-count'] as const,
    preferences: ['notification-preferences'] as const,
  },
  audit: {
    all: ['audit'] as const,
    list: (params: unknown) => ['audit', 'list', params] as const,
  },
} as const;

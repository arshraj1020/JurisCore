import { api } from '@/lib/api/client';
import type {
  AppNotification, NotificationPreferences, PageResponse,
} from '@/types/api';

export const notificationsApi = {
  list: (params: { unread?: boolean; page?: number; size?: number }) =>
    api.get<PageResponse<AppNotification>>('/api/v1/notifications', { ...params }),
  unreadCount: () => api.get<{ unread: number }>('/api/v1/notifications/unread-count'),
  markRead: (notificationId: string) =>
    api.post<AppNotification>(`/api/v1/notifications/${notificationId}/read`),
  markAllRead: () => api.post<{ marked: number }>('/api/v1/notifications/read-all'),
  remove: (notificationId: string) => api.delete<void>(`/api/v1/notifications/${notificationId}`),
  preferences: () => api.get<NotificationPreferences>('/api/v1/notification-preferences'),
  updatePreferences: (body: Partial<Omit<NotificationPreferences, 'version'>>) =>
    api.patch<NotificationPreferences>('/api/v1/notification-preferences', body),
};

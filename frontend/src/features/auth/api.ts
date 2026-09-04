import { api } from '@/lib/api/client';
import type {
  InviteUserRequest, Organization, PageResponse, Role, UpdateProfileRequest, User, UserStatus,
} from '@/types/api';

export const usersApi = {
  me: () => api.get<User>('/api/v1/users/me'),
  updateMe: (body: UpdateProfileRequest) => api.put<User>('/api/v1/users/me', body),
  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    api.post<void>('/api/v1/users/me/change-password', body),
  list: (params: { role?: string; search?: string; page?: number; size?: number }) =>
    api.get<PageResponse<User>>('/api/v1/users', { ...params }),
  invite: (body: InviteUserRequest) => api.post<User>('/api/v1/users/invite', body),
  changeStatus: (userId: string, status: UserStatus) =>
    api.patch<User>(`/api/v1/users/${userId}/status`, undefined, { status }),
  changeRole: (userId: string, role: Role) =>
    api.patch<User>(`/api/v1/users/${userId}/role`, undefined, { role }),
};

export const organizationApi = {
  current: () => api.get<Organization>('/api/v1/organizations/current'),
};

/**
 * The backend's contract, transcribed.
 *
 * Every type here mirrors a Java record or enum in the JurisCore backend. Nothing is
 * invented: if a field is not on the corresponding DTO it is not here, and if the backend
 * omits a field the type says so.
 *
 * Two backend-wide behaviours shape these declarations:
 *
 * 1. `spring.jackson.default-property-inclusion: non_null` — a null field is *absent*
 *    from the JSON rather than present as null. Optional fields are therefore written
 *    `field?: T | null`, which is the only spelling that is honest about both.
 * 2. `BigDecimal` and `Instant`/`LocalDate` serialise as JSON strings. Money is kept as a
 *    string all the way to the formatter: parsing an invoice total into a JavaScript
 *    number is how 11800.00 becomes 11799.999999999998, and the backend is the authority
 *    on these figures anyway.
 */

// ---------------------------------------------------------------- envelopes

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string | null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
}

export interface FieldViolation {
  field: string;
  message: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: FieldViolation[] | null;
  timestamp: string;
}

export interface ApiErrorResponse {
  success: false;
  error: ApiErrorBody;
}

/** An ISO-8601 instant, e.g. `2026-03-01T10:15:00Z`. */
export type IsoInstant = string;
/** An ISO-8601 calendar date, e.g. `2026-03-01`. */
export type IsoDate = string;
/** A decimal carried as a string so no precision is lost in transit. */
export type Decimal = string;

// ---------------------------------------------------------------- identity

export type Role = 'SUPER_ADMIN' | 'FIRM_ADMIN' | 'LAWYER' | 'CLERK' | 'CLIENT';
export type UserStatus = 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

export interface User {
  id: string;
  organizationId?: string | null;
  email: string;
  firstName: string;
  lastName: string;
  fullName: string;
  phone?: string | null;
  role: Role;
  status: UserStatus;
  lastLoginAt?: IsoInstant | null;
  createdAt: IsoInstant;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface LoginRequest { email: string; password: string }
export interface RegisterRequest {
  firmName: string;
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  phone?: string;
  timezone?: string;
}
export interface UpdateProfileRequest { firstName: string; lastName: string; phone?: string }
export interface ChangePasswordRequest { currentPassword: string; newPassword: string }
export interface InviteUserRequest {
  email: string; firstName: string; lastName: string; phone?: string; role: Role;
}

// ------------------------------------------------------------ organization

export type OrganizationStatus = 'ACTIVE' | 'SUSPENDED' | 'CLOSED';

export interface Organization {
  id: string;
  name: string;
  slug: string;
  status: OrganizationStatus;
  contactEmail?: string | null;
  contactPhone?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  country?: string | null;
  postalCode?: string | null;
  timezone?: string | null;
  registrationNumber?: string | null;
  createdAt: IsoInstant;
}

// ---------------------------------------------------------------- casework

export type ClientType = 'INDIVIDUAL' | 'CORPORATE';

export interface Client {
  id: string;
  displayName: string;
  clientType: ClientType;
  email?: string | null;
  phone?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  country?: string | null;
  postalCode?: string | null;
  notes?: string | null;
  deletedAt?: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
}

export interface ClientRequest {
  displayName: string;
  clientType: ClientType;
  email?: string | null;
  phone?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  country?: string | null;
  postalCode?: string | null;
  notes?: string | null;
}

export type CaseStatus = 'OPEN' | 'IN_PROGRESS' | 'ON_HOLD' | 'CLOSED';

export interface LegalCase {
  id: string;
  caseNumber: string;
  title: string;
  description?: string | null;
  clientId: string;
  status: CaseStatus;
  openedAt: IsoInstant;
  closedAt?: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  version: number;
}

export interface CreateCaseRequest { title: string; description?: string | null; clientId: string }
export interface UpdateCaseRequest {
  title: string; description?: string | null; clientId?: string | null; version: number;
}

export type CaseEventType =
  | 'CASE_CREATED' | 'LAWYER_ASSIGNED' | 'LAWYER_UNASSIGNED' | 'CASE_STATUS_CHANGED'
  | 'MANUAL_NOTE' | 'HEARING_SCHEDULED' | 'HEARING_COMPLETED' | 'HEARING_ADJOURNED'
  | 'HEARING_CANCELLED' | 'TASK_CREATED' | 'TASK_COMPLETED' | 'TASK_CANCELLED'
  | 'DEADLINE_CREATED' | 'DEADLINE_COMPLETED' | 'DEADLINE_CANCELLED'
  | 'DOCUMENT_UPLOADED' | 'DOCUMENT_DELETED';

export interface CaseEvent {
  id: string;
  caseId: string;
  eventType: CaseEventType;
  actorUserId?: string | null;
  occurredAt: IsoInstant;
  summary: string;
}

export interface CaseAssignment {
  id: string;
  caseId: string;
  lawyerUserId: string;
  lead: boolean;
  assignedAt: IsoInstant;
  assignedBy?: string | null;
}

// --------------------------------------------------------- case management

export type CourtType = 'SUPREME' | 'HIGH' | 'DISTRICT' | 'TRIBUNAL' | 'OTHER';

export interface Court {
  id: string;
  name: string;
  courtType: CourtType;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  country?: string | null;
  timezone?: string | null;
  active: boolean;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  version: number;
}

export interface CourtRequest {
  name: string;
  courtType: CourtType;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  country?: string | null;
  timezone?: string | null;
  version?: number | null;
}

export type HearingType = 'MENTION' | 'EVIDENCE' | 'ARGUMENTS' | 'JUDGMENT' | 'OTHER';
export type HearingStatus = 'SCHEDULED' | 'COMPLETED' | 'ADJOURNED' | 'CANCELLED';

export interface Hearing {
  id: string;
  caseId: string;
  courtId: string;
  hearingType: HearingType;
  status: HearingStatus;
  scheduledAt: IsoInstant;
  durationMinutes?: number | null;
  judgeName?: string | null;
  courtroom?: string | null;
  purpose?: string | null;
  outcome?: string | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  version: number;
}

export interface CreateHearingRequest {
  caseId: string;
  courtId: string;
  hearingType: HearingType;
  scheduledAt: IsoInstant;
  durationMinutes?: number | null;
  judgeName?: string | null;
  courtroom?: string | null;
  purpose?: string | null;
}

export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export interface Task {
  id: string;
  caseId: string;
  title: string;
  description?: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  assignedToUserId?: string | null;
  dueAt?: IsoInstant | null;
  completedAt?: IsoInstant | null;
  deletedAt?: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  version: number;
}

export interface CreateTaskRequest {
  title: string;
  description?: string | null;
  priority: TaskPriority;
  assignedToUserId?: string | null;
  dueAt?: IsoInstant | null;
}

export type DeadlineStatus = 'OPEN' | 'COMPLETED' | 'CANCELLED';
export type DeadlineType = 'COURT' | 'INTERNAL' | 'OTHER';

export interface Deadline {
  id: string;
  caseId: string;
  title: string;
  description?: string | null;
  deadlineType: DeadlineType;
  dueAt: IsoInstant;
  status: DeadlineStatus;
  completedAt?: IsoInstant | null;
  source?: string | null;
  deletedAt?: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  version: number;
}

export interface DeadlineRequest {
  title: string;
  description?: string | null;
  deadlineType: DeadlineType;
  dueAt: IsoInstant;
  source?: string | null;
  version?: number | null;
}

export type ReminderStatus = 'SCHEDULED' | 'SENT' | 'CANCELLED';
export type ReminderChannel = 'IN_APP' | 'EMAIL';

export interface Reminder {
  id: string;
  taskId?: string | null;
  deadlineId?: string | null;
  remindAt: IsoInstant;
  status: ReminderStatus;
  channel: ReminderChannel;
  note?: string | null;
  triggeredAt?: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  version: number;
}

export interface CreateReminderRequest {
  remindAt: IsoInstant;
  channel: ReminderChannel;
  note?: string | null;
}

// --------------------------------------------------------------- documents

export type DocumentStatus = 'UPLOADING' | 'AVAILABLE' | 'DELETED' | 'FAILED';

export interface CaseDocument {
  id: string;
  caseId: string;
  filename: string;
  contentType: string;
  fileSize: number;
  status: DocumentStatus;
  description?: string | null;
  uploadedAt?: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  createdBy?: string | null;
  updatedBy?: string | null;
  version: number;
}

export interface CreateDocumentRequest {
  filename: string;
  contentType: string;
  fileSize: number;
  description?: string | null;
}

/** The document row plus the one-time link its bytes must be PUT to. */
export interface DocumentUploadTicket {
  document: CaseDocument;
  uploadUrl: string;
  uploadMethod: string;
  requiredContentType: string;
  expiresAt: IsoInstant;
  expiresInSeconds: number;
}

export interface DocumentDownloadTicket {
  downloadUrl: string;
  filename: string;
  contentType: string;
  fileSize: number;
  expiresAt: IsoInstant;
  expiresInSeconds: number;
}

// ----------------------------------------------------------------- billing

export type InvoiceStatus =
  | 'DRAFT' | 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE' | 'CANCELLED';
export type PaymentMethod = 'CASH' | 'BANK_TRANSFER' | 'CARD' | 'UPI' | 'CHEQUE' | 'OTHER';

export interface InvoiceLineItem {
  id: string;
  description: string;
  quantity: Decimal;
  unitPrice: Decimal;
  amount: Decimal;
  taxRate: Decimal;
  taxAmount: Decimal;
  sortOrder: number;
}

export interface InvoiceLineItemRequest {
  description: string;
  quantity: string;
  unitPrice: string;
  taxRate?: string | null;
}

export interface Invoice {
  id: string;
  invoiceNumber: string;
  clientId: string;
  caseId?: string | null;
  status: InvoiceStatus;
  issueDate?: IsoDate | null;
  dueDate?: IsoDate | null;
  currency: string;
  subtotal: Decimal;
  taxAmount: Decimal;
  discountAmount: Decimal;
  totalAmount: Decimal;
  amountPaid: Decimal;
  amountDue: Decimal;
  notes?: string | null;
  paidAt?: IsoInstant | null;
  cancelledAt?: IsoInstant | null;
  /**
   * Absent on list responses: the backend omits line items from a page of invoices, so
   * `undefined` here means "not included", never "this invoice has none".
   */
  lineItems?: InvoiceLineItem[] | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  createdBy?: string | null;
  updatedBy?: string | null;
  version: number;
}

export interface CreateInvoiceRequest {
  clientId: string;
  caseId?: string | null;
  currency?: string | null;
  issueDate?: IsoDate | null;
  dueDate?: IsoDate | null;
  discountAmount?: string | null;
  notes?: string | null;
  lineItems: InvoiceLineItemRequest[];
}

export interface UpdateInvoiceRequest {
  version: number;
  clientId?: string | null;
  caseId?: string | null;
  issueDate?: IsoDate | null;
  dueDate?: IsoDate | null;
  discountAmount?: string | null;
  notes?: string | null;
  lineItems?: InvoiceLineItemRequest[] | null;
}

export interface IssueInvoiceRequest {
  version: number; issueDate?: IsoDate | null; dueDate?: IsoDate | null;
}
export interface CancelInvoiceRequest { version: number; reason?: string | null }

export interface Payment {
  id: string;
  invoiceId: string;
  amount: Decimal;
  currency: string;
  paymentDate: IsoDate;
  method: PaymentMethod;
  reference?: string | null;
  notes?: string | null;
  createdAt: IsoInstant;
  createdBy?: string | null;
}

export interface RecordPaymentRequest {
  amount: string;
  currency?: string | null;
  paymentDate?: IsoDate | null;
  method: PaymentMethod;
  reference?: string | null;
  notes?: string | null;
}

export interface BillingProfile {
  id?: string | null;
  legalName?: string | null;
  taxRegistration?: string | null;
  billingEmail?: string | null;
  billingPhone?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  country?: string | null;
  postalCode?: string | null;
  defaultCurrency: string;
  invoicePrefix: string;
  invoiceNotes?: string | null;
  createdAt?: IsoInstant | null;
  updatedAt?: IsoInstant | null;
  version?: number | null;
}

// ----------------------------------------------------------- notifications

export type NotificationType =
  | 'INVOICE_ISSUED' | 'INVOICE_PAID' | 'INVOICE_OVERDUE' | 'INVOICE_CANCELLED'
  | 'PAYMENT_RECEIVED' | 'CASE_ASSIGNED' | 'SYSTEM_MESSAGE';
export type NotificationCategory = 'INVOICE' | 'PAYMENT' | 'CASE' | 'SYSTEM';
export type NotificationSeverity = 'INFO' | 'SUCCESS' | 'WARNING' | 'CRITICAL';

export interface AppNotification {
  id: string;
  type: NotificationType;
  category: NotificationCategory;
  severity: NotificationSeverity;
  title: string;
  message: string;
  entityType?: string | null;
  entityId?: string | null;
  /** A relative in-app path. The backend rejects anything that does not start with `/`. */
  actionPath?: string | null;
  readAt?: IsoInstant | null;
  read: boolean;
  createdAt: IsoInstant;
}

export interface NotificationPreferences {
  invoice: boolean;
  payment: boolean;
  caseUpdates: boolean;
  system: boolean;
  version?: number | null;
}

// ------------------------------------------------------------------- audit

export interface AuditEvent {
  id: string;
  action: string;
  entityType: string;
  entityId?: string | null;
  actorUserId?: string | null;
  occurredAt: IsoInstant;
  requestId?: string | null;
  summary: string;
  recordedAt: IsoInstant;
}

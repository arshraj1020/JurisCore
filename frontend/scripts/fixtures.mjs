/** Fixture data shared by the screenshot and accessibility scripts. */

// ------------------------------------------------------------------ fixtures

export const envelope = (data) => ({ success: true, data });
export const page = (items, extra = {}) => ({
  items, page: 0, size: 20, totalItems: items.length,
  totalPages: items.length ? 1 : 0, hasNext: false, ...extra,
});

const user = {
  id: 'u1', organizationId: 'o1', email: 'asha@raopartners.example',
  firstName: 'Asha', lastName: 'Rao', fullName: 'Asha Rao', role: 'FIRM_ADMIN',
  status: 'ACTIVE', lastLoginAt: '2026-09-03T08:12:00Z', createdAt: '2026-01-05T09:00:00Z',
};
const lawyers = [
  { ...user, id: 'u2', firstName: 'Vikram', lastName: 'Mehta', fullName: 'Vikram Mehta', role: 'LAWYER' },
  { ...user, id: 'u3', firstName: 'Nisha', lastName: 'Kulkarni', fullName: 'Nisha Kulkarni', role: 'LAWYER', status: 'SUSPENDED' },
  { ...user, id: 'u4', firstName: 'Rohit', lastName: 'Sharma', fullName: 'Rohit Sharma', role: 'CLERK', lastLoginAt: null },
];

const clients = [
  { id: 'c1', displayName: 'Meridian Textiles Pvt Ltd', clientType: 'CORPORATE', email: 'legal@meridiantextiles.example', phone: '+91 22 5555 0142', addressLine1: '14 Marine Lines', city: 'Mumbai', state: 'Maharashtra', postalCode: '400020', country: 'India', createdAt: '2025-11-02T09:00:00Z', updatedAt: '2026-08-01T09:00:00Z' },
  { id: 'c2', displayName: 'Kavita Deshpande', clientType: 'INDIVIDUAL', email: 'kavita.d@example.test', phone: '+91 98200 11223', createdAt: '2026-02-18T09:00:00Z', updatedAt: '2026-08-11T09:00:00Z' },
  { id: 'c3', displayName: 'Northline Logistics LLP', clientType: 'CORPORATE', email: 'accounts@northline.example', createdAt: '2026-04-09T09:00:00Z', updatedAt: '2026-08-20T09:00:00Z' },
];

const cases = [
  { id: 'k1', caseNumber: 'MAT-2026-0041', title: 'Meridian Textiles v. Coastal Shipping — contract dispute', description: 'Breach of a carriage contract; claim for demurrage and consequential loss.', clientId: 'c1', status: 'IN_PROGRESS', openedAt: '2026-03-14T09:00:00Z', createdAt: '2026-03-14T09:00:00Z', updatedAt: '2026-08-30T09:00:00Z', version: 4 },
  { id: 'k2', caseNumber: 'MAT-2026-0052', title: 'Deshpande — succession certificate', clientId: 'c2', status: 'OPEN', openedAt: '2026-05-02T09:00:00Z', createdAt: '2026-05-02T09:00:00Z', updatedAt: '2026-08-02T09:00:00Z', version: 1 },
  { id: 'k3', caseNumber: 'MAT-2026-0063', title: 'Northline Logistics — GST assessment appeal', clientId: 'c3', status: 'ON_HOLD', openedAt: '2026-06-21T09:00:00Z', createdAt: '2026-06-21T09:00:00Z', updatedAt: '2026-08-25T09:00:00Z', version: 2 },
];

const courts = [
  { id: 'ct1', name: 'Bombay High Court', courtType: 'HIGH', city: 'Mumbai', state: 'Maharashtra', country: 'India', active: true, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z', version: 0 },
  { id: 'ct2', name: 'City Civil Court, Dindoshi', courtType: 'DISTRICT', city: 'Mumbai', state: 'Maharashtra', country: 'India', active: true, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z', version: 0 },
  { id: 'ct3', name: 'Small Causes Court (old bench)', courtType: 'OTHER', city: 'Mumbai', active: false, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z', version: 1 },
];

const soon = (days, hour = 10) => {
  const d = new Date();
  d.setDate(d.getDate() + days);
  d.setHours(hour, 30, 0, 0);
  return d.toISOString();
};

const hearings = [
  { id: 'h1', caseId: 'k1', courtId: 'ct1', hearingType: 'ARGUMENTS', status: 'SCHEDULED', scheduledAt: soon(2), durationMinutes: 90, judgeName: 'Justice R. Kamat', courtroom: 'Court 14', purpose: 'Final arguments on the interim application.', createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-01T09:00:00Z', version: 0 },
  { id: 'h2', caseId: 'k3', courtId: 'ct2', hearingType: 'MENTION', status: 'SCHEDULED', scheduledAt: soon(5, 11), durationMinutes: 20, courtroom: 'Court 3', createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-01T09:00:00Z', version: 0 },
  { id: 'h3', caseId: 'k2', courtId: 'ct2', hearingType: 'EVIDENCE', status: 'ADJOURNED', scheduledAt: soon(-9), outcome: 'Adjourned at the request of the opposite party.', createdAt: '2026-07-01T09:00:00Z', updatedAt: '2026-08-01T09:00:00Z', version: 1 },
];

const tasks = [
  { id: 't1', caseId: 'k1', title: 'Draft the rejoinder', description: 'Cover paragraphs 12–18 of the reply.', status: 'IN_PROGRESS', priority: 'HIGH', dueAt: soon(1), createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-28T09:00:00Z', version: 1 },
  { id: 't2', caseId: 'k1', title: 'Brief senior counsel', status: 'TODO', priority: 'URGENT', dueAt: soon(3), createdAt: '2026-08-22T09:00:00Z', updatedAt: '2026-08-22T09:00:00Z', version: 0 },
  { id: 't3', caseId: 'k1', title: 'File the vakalatnama', status: 'COMPLETED', priority: 'MEDIUM', completedAt: '2026-08-10T09:00:00Z', createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-10T09:00:00Z', version: 2 },
];

const deadlines = [
  { id: 'd1', caseId: 'k1', title: 'Limitation for the appeal', deadlineType: 'COURT', dueAt: soon(11), status: 'OPEN', source: 'Order dated 14 August 2026', createdAt: '2026-08-14T09:00:00Z', updatedAt: '2026-08-14T09:00:00Z', version: 0 },
  { id: 'd2', caseId: 'k1', title: 'Client sign-off on the affidavit', deadlineType: 'INTERNAL', dueAt: soon(-2), status: 'OPEN', createdAt: '2026-08-05T09:00:00Z', updatedAt: '2026-08-05T09:00:00Z', version: 0 },
];

const documents = [
  { id: 'g1', caseId: 'k1', filename: 'plaint-as-filed.pdf', contentType: 'application/pdf', fileSize: 2481232, status: 'AVAILABLE', description: 'As filed on 14 March, with the court stamp.', uploadedAt: '2026-03-14T11:20:00Z', createdAt: '2026-03-14T11:00:00Z', updatedAt: '2026-03-14T11:20:00Z', version: 1 },
  { id: 'g2', caseId: 'k1', filename: 'carriage-contract-2024.docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', fileSize: 184320, status: 'AVAILABLE', uploadedAt: '2026-03-20T09:00:00Z', createdAt: '2026-03-20T09:00:00Z', updatedAt: '2026-03-20T09:00:00Z', version: 0 },
  { id: 'g3', caseId: 'k1', filename: 'survey-photographs.zip', contentType: 'application/zip', fileSize: 41234567, status: 'FAILED', createdAt: '2026-08-29T09:00:00Z', updatedAt: '2026-08-29T09:00:00Z', version: 0 },
];

const invoices = [
  { id: 'i1', invoiceNumber: 'INV-2026-0031', clientId: 'c1', caseId: 'k1', status: 'OVERDUE', issueDate: '2026-07-01', dueDate: '2026-07-31', currency: 'INR', subtotal: '180000.00', taxAmount: '32400.00', discountAmount: '0.00', totalAmount: '212400.00', amountPaid: '50000.00', amountDue: '162400.00', notes: 'Payable by NEFT to the account on file. Interest at 12% p.a. applies after the due date.', lineItems: [ { id: 'l1', description: 'Drafting and settling the plaint', quantity: '20.00', unitPrice: '6000.00', amount: '120000.00', taxRate: '18.00', taxAmount: '21600.00', sortOrder: 0 }, { id: 'l2', description: 'Conference with senior counsel', quantity: '4.00', unitPrice: '15000.00', amount: '60000.00', taxRate: '18.00', taxAmount: '10800.00', sortOrder: 1 } ], createdAt: '2026-07-01T09:00:00Z', updatedAt: '2026-08-01T09:00:00Z', version: 3 },
  { id: 'i2', invoiceNumber: 'INV-2026-0042', clientId: 'c3', status: 'PAID', issueDate: '2026-07-20', dueDate: '2026-08-19', currency: 'INR', subtotal: '45000.00', taxAmount: '8100.00', discountAmount: '0.00', totalAmount: '53100.00', amountPaid: '53100.00', amountDue: '0.00', createdAt: '2026-07-20T09:00:00Z', updatedAt: '2026-08-19T09:00:00Z', version: 2 },
  { id: 'i3', invoiceNumber: 'INV-2026-0048', clientId: 'c2', status: 'DRAFT', currency: 'INR', subtotal: '30000.00', taxAmount: '5400.00', discountAmount: '0.00', totalAmount: '35400.00', amountPaid: '0.00', amountDue: '35400.00', createdAt: '2026-08-28T09:00:00Z', updatedAt: '2026-08-28T09:00:00Z', version: 0 },
];

const notifications = [
  { id: 'n1', type: 'INVOICE_OVERDUE', category: 'INVOICE', severity: 'CRITICAL', title: 'INV-2026-0031 is overdue', message: '₹1,62,400.00 outstanding since 31 July.', actionPath: '/invoices/i1', read: false, createdAt: '2026-09-01T06:00:00Z' },
  { id: 'n2', type: 'PAYMENT_RECEIVED', category: 'PAYMENT', severity: 'SUCCESS', title: 'Payment recorded', message: '₹53,100.00 received against INV-2026-0042.', read: false, createdAt: '2026-08-19T10:30:00Z' },
  { id: 'n3', type: 'CASE_ASSIGNED', category: 'CASE', severity: 'INFO', title: 'You were assigned to MAT-2026-0063', message: 'Northline Logistics — GST assessment appeal.', actionPath: '/cases/k3', read: true, createdAt: '2026-08-14T09:15:00Z' },
];

const audit = [
  { id: 'a1', action: 'INVOICE_ISSUED', entityType: 'Invoice', entityId: 'i1', actorUserId: 'u1', occurredAt: '2026-07-01T09:02:11Z', requestId: 'b1f4c2a9', summary: 'Invoice INV-2026-0031 issued to Meridian Textiles Pvt Ltd', recordedAt: '2026-07-01T09:02:11Z' },
  { id: 'a2', action: 'PAYMENT_RECORDED', entityType: 'Payment', entityId: 'p1', actorUserId: 'u1', occurredAt: '2026-08-19T10:30:02Z', requestId: '77aa10de', summary: 'Payment of INR 53,100.00 recorded against INV-2026-0042', recordedAt: '2026-08-19T10:30:02Z' },
  { id: 'a3', action: 'CASE_STATUS_CHANGED', entityType: 'LegalCase', entityId: 'k3', actorUserId: 'u2', occurredAt: '2026-08-25T12:04:44Z', requestId: '2c9be014', summary: 'MAT-2026-0063 moved from In progress to On hold', recordedAt: '2026-08-25T12:04:44Z' },
  { id: 'a4', action: 'DOCUMENT_UPLOADED', entityType: 'Document', entityId: 'g2', actorUserId: 'u2', occurredAt: '2026-03-20T09:00:31Z', requestId: '5ee0a3b7', summary: 'carriage-contract-2024.docx uploaded to MAT-2026-0041', recordedAt: '2026-03-20T09:00:31Z' },
];

const timeline = [
  { id: 'e1', caseId: 'k1', eventType: 'HEARING_ADJOURNED', actorUserId: 'u2', occurredAt: '2026-08-25T11:00:00Z', summary: 'Hearing of 25 August adjourned at the request of the opposite party' },
  { id: 'e2', caseId: 'k1', eventType: 'DOCUMENT_UPLOADED', actorUserId: 'u2', occurredAt: '2026-08-20T09:00:00Z', summary: 'carriage-contract-2024.docx uploaded' },
  { id: 'e3', caseId: 'k1', eventType: 'MANUAL_NOTE', actorUserId: 'u1', occurredAt: '2026-08-12T14:20:00Z', summary: 'Client confirmed they will not settle below ₹18,00,000.' },
  { id: 'e4', caseId: 'k1', eventType: 'CASE_CREATED', actorUserId: 'u1', occurredAt: '2026-03-14T09:00:00Z', summary: 'Matter opened for Meridian Textiles Pvt Ltd' },
];

export const routes = [
  [/\/api\/v1\/auth\/refresh$/, () => envelope({ accessToken: 'a', refreshToken: 'r', tokenType: 'Bearer', expiresIn: 300, user })],
  [/\/api\/v1\/organizations\/current$/, () => envelope({ id: 'o1', name: 'Rao & Partners', slug: 'rao-partners', status: 'ACTIVE', createdAt: '2025-01-01T00:00:00Z' })],
  [/\/api\/v1\/notifications\/unread-count$/, () => envelope({ unread: 2 })],
  [/\/api\/v1\/notification-preferences$/, () => envelope({ invoice: true, payment: true, caseUpdates: true, system: false, version: 1 })],
  [/\/api\/v1\/notifications/, () => envelope(page(notifications))],
  [/\/api\/v1\/audit/, () => envelope(page(audit))],
  [/\/api\/v1\/users/, () => envelope(page([user, ...lawyers]))],
  [/\/api\/v1\/clients\/c\d/, (u) => envelope(clients.find((c) => u.endsWith(c.id)) ?? clients[0])],
  [/\/api\/v1\/clients/, () => envelope(page(clients))],
  [/\/api\/v1\/cases\/[^/]+\/timeline/, () => envelope(page(timeline))],
  [/\/api\/v1\/cases\/[^/]+\/assignments/, () => envelope([
    { id: 'as1', caseId: 'k1', lawyerUserId: 'u2', lead: true, assignedAt: '2026-03-14T09:00:00Z' },
    { id: 'as2', caseId: 'k1', lawyerUserId: 'u3', lead: false, assignedAt: '2026-04-02T09:00:00Z' },
  ])],
  [/\/api\/v1\/cases\/[^/]+\/tasks/, () => envelope(page(tasks))],
  [/\/api\/v1\/cases\/[^/]+\/deadlines/, () => envelope(page(deadlines))],
  [/\/api\/v1\/cases\/[^/]+\/documents/, () => envelope(page(documents))],
  [/\/api\/v1\/cases\/k\d/, (u) => envelope(cases.find((c) => u.includes(c.id)) ?? cases[0])],
  [/\/api\/v1\/cases/, (u) => envelope(page(u.includes('status=OPEN') ? [cases[1]] : cases))],
  [/\/api\/v1\/courts/, () => envelope(page(courts))],
  [/\/api\/v1\/hearings/, () => envelope(page(hearings))],
  [/\/api\/v1\/reminders/, () => envelope(page([
    { id: 'r1', taskId: 't1', remindAt: soon(1, 9), status: 'SCHEDULED', channel: 'IN_APP', note: 'Rejoinder due tomorrow', createdAt: '2026-08-20T09:00:00Z', updatedAt: '2026-08-20T09:00:00Z', version: 0 },
    { id: 'r2', deadlineId: 'd2', remindAt: soon(-1, 9), status: 'SCHEDULED', channel: 'IN_APP', note: 'Chase the client for sign-off', createdAt: '2026-08-05T09:00:00Z', updatedAt: '2026-08-05T09:00:00Z', version: 0 },
  ]))],
  [/\/api\/v1\/invoices\/i\d\/payments/, () => envelope(page([
    { id: 'p1', invoiceId: 'i1', amount: '50000.00', currency: 'INR', paymentDate: '2026-07-25', method: 'BANK_TRANSFER', reference: 'NEFT/2026/44120', createdAt: '2026-07-25T09:00:00Z' },
  ]))],
  [/\/api\/v1\/invoices\/i\d/, (u) => envelope(invoices.find((i) => u.includes(i.id)) ?? invoices[0])],
  [/\/api\/v1\/invoices/, (u) => envelope(page(u.includes('status=OVERDUE') ? [invoices[0]] : invoices))],
  [/\/api\/v1\/billing\/profile/, () => envelope({ id: 'b1', legalName: 'Rao & Partners, Advocates', taxRegistration: '27AABCR1234M1Z5', billingEmail: 'accounts@raopartners.example', billingPhone: '+91 22 5555 0100', addressLine1: '4th Floor, Fort Chambers', addressLine2: '19 Homi Modi Street', city: 'Mumbai', state: 'Maharashtra', postalCode: '400001', country: 'India', defaultCurrency: 'INR', invoicePrefix: 'INV', invoiceNotes: 'Payable within 30 days by NEFT.', version: 2 })],
];


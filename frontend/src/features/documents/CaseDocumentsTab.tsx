import { useCallback, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { documentsApi } from './api';
import { uploadToPresignedUrl } from '@/lib/api/client';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import { Button, Card, CardHeader, Field, Input, Textarea } from '@/components/ui/primitives';
import { AsyncSection, EmptyState, TableSkeleton } from '@/components/ui/states';
import { ConfirmDialog, Dialog } from '@/components/ui/Dialog';
import { DocumentStatusBadge } from '@/components/ui/StatusBadge';
import { formatDateTime, formatFileSize } from '@/lib/format';
import { ApiError, messageFor } from '@/lib/api/errors';
import type { CaseDocument } from '@/types/api';

/**
 * Documents move through the Phase 4 presigned flow, never through the Spring API:
 *
 *   1. `POST /cases/{id}/documents` registers the row and returns a one-time upload link
 *      with the method and content type the signature covers.
 *   2. The bytes go straight to storage on that link. It carries its own authorisation,
 *      so the request must NOT include our Authorization header — `uploadToPresignedUrl`
 *      is a bare XMLHttpRequest for that reason, and it is also what gives us progress.
 *   3. `POST /documents/{id}/complete` tells the backend the bytes landed; only then does
 *      the row become AVAILABLE.
 *
 * Storage credentials never reach this code — a signed URL is all the browser ever sees,
 * and it expires. A link that has gone stale comes back as UPLOAD_LINK_EXPIRED, and the
 * retry restarts at step 1 to mint a fresh one rather than replaying a dead signature.
 */

type UploadPhase = 'registering' | 'uploading' | 'finalising' | 'failed';

interface UploadJob {
  key: string;
  file: File;
  phase: UploadPhase;
  percent: number;
  error?: string;
  expired?: boolean;
}

const MAX_BYTES = 50 * 1024 * 1024;

export function CaseDocumentsTab({ caseId }: { caseId: string }) {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [jobs, setJobs] = useState<UploadJob[]>([]);
  const [editing, setEditing] = useState<CaseDocument | null>(null);
  const [deleting, setDeleting] = useState<CaseDocument | null>(null);

  const mayUpload = can(user?.role, 'uploadDocuments');
  const mayDelete = can(user?.role, 'deleteDocuments');

  const query = useQuery({
    queryKey: keys.documents.forCase(caseId, {}),
    queryFn: () => documentsApi.listForCase(caseId, { size: 50 }),
  });

  const patchJob = useCallback((key: string, patch: Partial<UploadJob>) => {
    setJobs((current) => current.map((job) => (job.key === key ? { ...job, ...patch } : job)));
  }, []);

  const runUpload = useCallback(async (key: string, file: File) => {
    try {
      patchJob(key, { phase: 'registering', percent: 0, error: undefined, expired: false });
      const ticket = await documentsApi.register(caseId, {
        filename: file.name,
        // Browsers leave `type` empty for unrecognised extensions; the backend needs one.
        contentType: file.type || 'application/octet-stream',
        fileSize: file.size,
      });

      patchJob(key, { phase: 'uploading' });
      await uploadToPresignedUrl(
        ticket.uploadUrl,
        ticket.uploadMethod,
        file,
        ticket.requiredContentType,
        (percent) => patchJob(key, { percent }),
      );

      patchJob(key, { phase: 'finalising', percent: 100 });
      await documentsApi.complete(ticket.document.id);

      setJobs((current) => current.filter((job) => job.key !== key));
      await queryClient.invalidateQueries({ queryKey: keys.documents.all });
      await queryClient.invalidateQueries({ queryKey: ['cases', caseId, 'timeline'] });
      toast.success(`${file.name} uploaded`);
    } catch (error) {
      const expired = error instanceof ApiError && error.code === 'UPLOAD_LINK_EXPIRED';
      patchJob(key, { phase: 'failed', error: messageFor(error), expired });
    }
  }, [caseId, patchJob, queryClient, toast]);

  const addFiles = (files: FileList | null) => {
    if (!files) return;
    for (const file of Array.from(files)) {
      if (file.size === 0) {
        toast.error(`${file.name} is empty.`);
        continue;
      }
      if (file.size > MAX_BYTES) {
        toast.error(`${file.name} is larger than ${formatFileSize(MAX_BYTES)}.`);
        continue;
      }
      const key = `${file.name}:${file.size}:${Date.now()}:${Math.random().toString(36).slice(2)}`;
      setJobs((current) => [...current, { key, file, phase: 'registering', percent: 0 }]);
      void runUpload(key, file);
    }
    if (inputRef.current) inputRef.current.value = '';
  };

  const download = useMutation({
    mutationFn: (documentId: string) => documentsApi.download(documentId),
    onSuccess: (ticket) => {
      // The ticket is a short-lived signed link; the browser fetches the bytes itself.
      window.open(ticket.downloadUrl, '_blank', 'noopener,noreferrer');
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  const remove = useMutation({
    mutationFn: (documentId: string) => documentsApi.remove(documentId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.documents.all });
      setDeleting(null);
      toast.success('Document deleted');
    },
    onError: (error) => toast.error(messageFor(error)),
  });

  return (
    <Card>
      <CardHeader
        title="Documents"
        description="Files held against this matter."
        actions={mayUpload && (
          <>
            <input
              ref={inputRef}
              type="file"
              multiple
              className="sr-only"
              onChange={(event) => addFiles(event.target.files)}
              aria-label="Choose files to upload"
            />
            <Button size="sm" onClick={() => inputRef.current?.click()}>Upload files</Button>
          </>
        )}
      />

      {jobs.length > 0 && (
        <ul className="divide-y divide-ink-100 border-b border-ink-200 bg-ink-50/60">
          {jobs.map((job) => (
            <li key={job.key} className="px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="truncate text-sm font-medium text-ink-900">{job.file.name}</span>
                <span className="text-xs text-ink-500">{formatFileSize(job.file.size)}</span>
              </div>

              {job.phase === 'failed' ? (
                <div className="mt-2 flex flex-wrap items-center gap-3">
                  <p role="alert" className="text-sm text-red-700">
                    {job.expired
                      ? 'The upload link expired before the file finished. Retrying gets a new one.'
                      : job.error}
                  </p>
                  <Button size="sm" variant="secondary"
                    onClick={() => void runUpload(job.key, job.file)}>
                    Retry
                  </Button>
                  <Button size="sm" variant="ghost"
                    onClick={() => setJobs((c) => c.filter((j) => j.key !== job.key))}>
                    Dismiss
                  </Button>
                </div>
              ) : (
                <div className="mt-2">
                  <div
                    role="progressbar"
                    aria-label={`Uploading ${job.file.name}`}
                    aria-valuenow={job.phase === 'uploading' ? job.percent : undefined}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    className="h-1.5 overflow-hidden rounded-full bg-ink-200"
                  >
                    <div
                      className="h-full rounded-full bg-brand-600 transition-[width] duration-200"
                      style={{ width: `${job.phase === 'registering' ? 4 : job.percent}%` }}
                    />
                  </div>
                  <p className="mt-1 text-xs text-ink-500">
                    {job.phase === 'registering' && 'Preparing…'}
                    {job.phase === 'uploading' && `Uploading — ${job.percent}%`}
                    {job.phase === 'finalising' && 'Finishing…'}
                  </p>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <AsyncSection
        isLoading={query.isPending}
        error={query.error}
        data={query.data}
        isEmpty={(data) => data.items.length === 0}
        onRetry={() => query.refetch()}
        skeleton={<TableSkeleton rows={3} columns={3} />}
        empty={(
          <EmptyState
            title="No documents"
            description={mayUpload
              ? 'Upload the first file for this matter.'
              : 'Nothing has been filed against this matter yet.'}
          />
        )}
      >
        {(data) => (
          <ul className="divide-y divide-ink-100">
            {data.items.map((doc) => (
              <li key={doc.id} className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate text-sm font-medium text-ink-900">
                      {doc.filename}
                    </span>
                    <DocumentStatusBadge status={doc.status} />
                  </div>
                  {doc.description && (
                    <p className="mt-1 text-sm text-ink-600">{doc.description}</p>
                  )}
                  <p className="mt-1 text-xs text-ink-500">
                    {formatFileSize(doc.fileSize)}
                    <span className="text-ink-400"> · </span>
                    {doc.contentType}
                    {doc.uploadedAt && (
                      <>
                        <span className="text-ink-400"> · </span>
                        Uploaded {formatDateTime(doc.uploadedAt)}
                      </>
                    )}
                  </p>
                  {doc.status === 'FAILED' && (
                    <p className="mt-1 text-xs text-red-700">
                      The bytes never arrived in storage. Upload the file again.
                    </p>
                  )}
                </div>
                <div className="flex flex-wrap gap-1">
                  {doc.status === 'AVAILABLE' && (
                    <Button
                      size="sm" variant="secondary"
                      loading={download.isPending && download.variables === doc.id}
                      onClick={() => download.mutate(doc.id)}
                    >
                      Download
                    </Button>
                  )}
                  {mayUpload && doc.status !== 'DELETED' && (
                    <Button size="sm" variant="ghost" onClick={() => setEditing(doc)}>
                      Edit
                    </Button>
                  )}
                  {mayDelete && doc.status !== 'DELETED' && (
                    <Button size="sm" variant="ghost" onClick={() => setDeleting(doc)}>
                      Delete
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </AsyncSection>

      {editing && (
        <EditDocumentDialog
          doc={editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            await queryClient.invalidateQueries({ queryKey: keys.documents.all });
            setEditing(null);
            toast.success('Document updated');
          }}
        />
      )}

      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={() => deleting && remove.mutate(deleting.id)}
        title="Delete this document?"
        description={deleting
          ? `“${deleting.filename}” will no longer be available on this matter.`
          : ''}
        confirmLabel="Delete"
        busy={remove.isPending}
      />
    </Card>
  );
}

function EditDocumentDialog({ doc, onClose, onSaved }: {
  doc: CaseDocument;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}) {
  const toast = useToast();
  const [filename, setFilename] = useState(doc.filename);
  const [description, setDescription] = useState(doc.description ?? '');

  const save = useMutation({
    // `version` carries the optimistic lock; a stale copy is refused with 409, not merged.
    mutationFn: () => documentsApi.rename(
      doc.id, filename.trim(), description.trim() || null, doc.version,
    ),
    onSuccess: () => void onSaved(),
    onError: (error) => toast.error(messageFor(error)),
  });

  return (
    <Dialog
      open
      onClose={onClose}
      title="Edit document"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button
            loading={save.isPending}
            disabled={filename.trim().length === 0}
            onClick={() => save.mutate()}
          >
            Save
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Filename" required>
          {({ id }) => (
            <Input id={id} autoFocus value={filename}
              onChange={(event) => setFilename(event.target.value)} />
          )}
        </Field>
        <Field label="Description">
          {({ id }) => (
            <Textarea id={id} rows={3} value={description}
              onChange={(event) => setDescription(event.target.value)} />
          )}
        </Field>
      </div>
    </Dialog>
  );
}

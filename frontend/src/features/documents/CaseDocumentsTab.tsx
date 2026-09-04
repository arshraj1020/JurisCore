import { useCallback, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { documentsApi } from './api';
import { uploadToPresignedUrl } from '@/lib/api/client';
import { keys } from '@/lib/api/queryKeys';
import { useAuth } from '@/lib/auth/AuthContext';
import { can } from '@/lib/auth/roles';
import { useToast } from '@/components/ui/Toast';
import {
  Badge, Button, Card, CardHeader, Field, Input, Textarea,
} from '@/components/ui/primitives';
import { Icon } from '@/components/ui/icons';
import { cn } from '@/lib/cn';
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

/** A short type label for the row: the extension if there is one, else the MIME subtype. */
function extensionOf(filename: string, contentType: string): string {
  const dot = filename.lastIndexOf('.');
  if (dot > 0 && dot < filename.length - 1) {
    return filename.slice(dot + 1).toUpperCase().slice(0, 8);
  }
  const subtype = contentType.split('/')[1] ?? contentType;
  return subtype.toUpperCase().slice(0, 8);
}

export function CaseDocumentsTab({ caseId }: { caseId: string }) {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [jobs, setJobs] = useState<UploadJob[]>([]);
  const [editing, setEditing] = useState<CaseDocument | null>(null);
  const [deleting, setDeleting] = useState<CaseDocument | null>(null);
  // A counter, not a boolean: dragging over a child element fires `dragleave` on the
  // parent, and a boolean makes the highlight flicker across the zone's own contents.
  const [dragDepth, setDragDepth] = useState(0);

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
        icon="document"
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
            <Button size="sm" icon="upload" onClick={() => inputRef.current?.click()}>
              Upload files
            </Button>
          </>
        )}
      />

      {mayUpload && (
        // Drag and drop over a labelled button, not instead of one: dropping is a
        // convenience for people who have a file manager open, and the button is what
        // works from a keyboard and on a phone.
        <div
          className="border-b border-ink-200 p-3"
          onDragEnter={(event) => { event.preventDefault(); setDragDepth((d) => d + 1); }}
          onDragOver={(event) => event.preventDefault()}
          onDragLeave={() => setDragDepth((d) => Math.max(0, d - 1))}
          onDrop={(event) => {
            event.preventDefault();
            setDragDepth(0);
            addFiles(event.dataTransfer.files);
          }}
        >
          <div className={cn(
            'flex flex-col items-center gap-1.5 rounded-lg border border-dashed px-4 py-6 text-center transition-colors',
            dragDepth > 0 ? 'border-brand-400 bg-brand-50' : 'border-ink-300 bg-ink-50/50',
          )}>
            <Icon name="upload" className={cn('h-5 w-5', dragDepth > 0 ? 'text-brand-600' : 'text-ink-400')} />
            <p className="text-sm text-ink-700">
              Drop files here, or{' '}
              <button
                type="button"
                onClick={() => inputRef.current?.click()}
                className="rounded font-medium text-brand-700 underline underline-offset-2 hover:text-brand-800"
              >
                choose from your computer
              </button>
            </p>
            <p className="text-xs text-ink-500">
              Up to {formatFileSize(MAX_BYTES)} per file. Files go straight to storage over a
              one-time link.
            </p>
          </div>
        </div>
      )}

      {jobs.length > 0 && (
        <ul className="divide-y divide-ink-100 border-b border-ink-200 bg-ink-50/60">
          {jobs.map((job) => (
            <li key={job.key} className="flex items-start gap-3 px-4 py-3">
              <span className={cn(
                'mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-md',
                job.phase === 'failed' ? 'bg-red-50 text-red-600' : 'bg-white text-ink-400 ring-1 ring-ink-200',
              )}>
                <Icon name={job.phase === 'failed' ? 'alert' : 'document'} className="h-4 w-4" />
              </span>

              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <span className="truncate text-sm font-medium text-ink-900">{job.file.name}</span>
                  <span className="text-xs tabular-nums text-ink-500">
                    {formatFileSize(job.file.size)}
                  </span>
                </div>

                {job.phase === 'failed' ? (
                  <div className="mt-1.5 flex flex-wrap items-center gap-2">
                    <p role="alert" className="flex-1 text-xs text-red-700">
                      {job.expired
                        ? 'The upload link expired before the file finished. Retrying gets a new one.'
                        : job.error}
                    </p>
                    <Button size="xs" variant="secondary" icon="refresh"
                      onClick={() => void runUpload(job.key, job.file)}>
                      Retry
                    </Button>
                    <Button size="xs" variant="ghost"
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
                      className="h-1 overflow-hidden rounded-full bg-ink-200"
                    >
                      <div
                        className={cn(
                          'h-full rounded-full bg-brand-600 transition-[width] duration-200',
                          job.phase !== 'uploading' && 'animate-pulse',
                        )}
                        style={{ width: `${job.phase === 'registering' ? 6 : job.percent}%` }}
                      />
                    </div>
                    <p className="mt-1 text-xs text-ink-500">
                      {job.phase === 'registering' && 'Preparing a one-time upload link…'}
                      {job.phase === 'uploading' && `Uploading to storage — ${job.percent}%`}
                      {job.phase === 'finalising' && 'Confirming the upload…'}
                    </p>
                  </div>
                )}
              </div>
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
            compact icon="document"
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
              <li key={doc.id}
                className="flex flex-wrap items-start gap-3 px-4 py-3 transition-colors hover:bg-ink-50/60">
                <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-md bg-ink-100 text-ink-500">
                  <Icon name="document" className="h-4 w-4" />
                </span>

                <div className="min-w-0 basis-full sm:flex-1 sm:basis-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate text-sm font-medium text-ink-900">
                      {doc.filename}
                    </span>
                    <DocumentStatusBadge status={doc.status} />
                    <Badge tone="neutral">{extensionOf(doc.filename, doc.contentType)}</Badge>
                  </div>
                  {doc.description && (
                    <p className="mt-1 text-sm text-ink-600">{doc.description}</p>
                  )}
                  <p className="mt-1 text-xs text-ink-500">
                    <span className="tabular-nums">{formatFileSize(doc.fileSize)}</span>
                    {doc.uploadedAt && (
                      <>
                        <span className="text-ink-300"> · </span>
                        Uploaded {formatDateTime(doc.uploadedAt)}
                      </>
                    )}
                  </p>
                  {doc.status === 'FAILED' && (
                    <p className="mt-1.5 text-xs text-red-700">
                      The bytes never arrived in storage. Upload the file again.
                    </p>
                  )}
                </div>

                <div className="flex w-full flex-wrap gap-1 sm:w-auto sm:justify-end">
                  {doc.status === 'AVAILABLE' && (
                    <Button
                      size="sm" variant="secondary" icon="download"
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

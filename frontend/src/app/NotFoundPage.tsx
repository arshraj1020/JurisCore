import { Link } from 'react-router-dom';
import { Card } from '@/components/ui/primitives';
import { EmptyState } from '@/components/ui/states';

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-xl py-8">
      <Card>
        <EmptyState
          icon="search"
          title="Page not found"
          description="That address does not match anything in the workspace. It may have been renamed, or the record may have been removed."
          action={
            <Link
              to="/"
              className="inline-flex h-9 items-center rounded-md bg-white px-3.5 text-sm font-medium text-ink-800 shadow-raised ring-1 ring-inset ring-ink-300 transition-colors hover:bg-ink-50"
            >
              Back to dashboard
            </Link>
          }
        />
      </Card>
    </div>
  );
}

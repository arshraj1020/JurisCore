import { Link } from 'react-router-dom';
import { EmptyState } from '@/components/ui/states';

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-lg py-16">
      <EmptyState
        title="Page not found"
        description="That address does not match anything in the workspace."
        action={
          <Link
            to="/"
            className="inline-flex h-10 items-center rounded-md bg-white px-4 text-sm font-medium text-ink-800 ring-1 ring-inset ring-ink-300 hover:bg-ink-50"
          >
            Back to dashboard
          </Link>
        }
      />
    </div>
  );
}

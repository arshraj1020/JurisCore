import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { Button } from '@/components/ui/primitives';

interface Props {
  children: ReactNode;
  /** Replaces the default recovery screen. Called with a reset that re-renders children. */
  fallback?: (error: Error, reset: () => void) => ReactNode;
  /** Reporting hook. Kept injectable so tests can assert without a console spy. */
  onError?: (error: Error, info: ErrorInfo) => void;
}

interface State {
  error: Error | null;
}

/**
 * The last line of defence between one bad render and a blank page.
 *
 * React's contract is unforgiving: an exception thrown during render, in a lifecycle
 * method or in a constructor unmounts the *entire* tree unless an error boundary is above
 * it. So a single field arriving in an unexpected shape — one `quantity.replace is not a
 * function` deep inside an invoice line — does not damage the invoice page, it removes
 * the application, navigation and all, leaving a white screen with a stack trace in a
 * console nobody has open. That is a wildly disproportionate blast radius, and the only
 * thing that contains it is a boundary.
 *
 * This one sits at the top of the tree, above the router, because the failure it exists
 * for is the one nobody predicted; a boundary placed only around the parts we already
 * suspect is a boundary around the bugs we have already fixed.
 *
 * It is a class because it has to be. `getDerivedStateFromError` and `componentDidCatch`
 * have no hook equivalent — this is the one part of React with no function-component
 * spelling.
 *
 * What it deliberately does not do is retry by itself or swallow the error quietly. The
 * user is told the screen failed, offered a way back, and the error still reaches the
 * console for whoever is looking.
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    this.props.onError?.(error, info);
    // Kept: a boundary that renders a friendly message and drops the stack makes the
    // failure harder to diagnose than no boundary at all.
    console.error('Unhandled rendering error', error, info.componentStack);
  }

  private readonly reset = (): void => {
    this.setState({ error: null });
  };

  override render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    if (this.props.fallback) return this.props.fallback(error, this.reset);

    return (
      <div role="alert" className="flex min-h-screen items-center justify-center bg-ink-50 p-6">
        <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-raised ring-1 ring-ink-200">
          <h1 className="text-base font-semibold text-ink-900">Something went wrong</h1>
          <p className="mt-2 text-sm text-ink-600">
            This screen could not be displayed. Your work has not been lost — nothing was
            saved or changed by this error.
          </p>
          {/*
            The message, not the stack. It is often the one line that tells support what
            happened, and it is not sensitive in the way a full trace can be.
          */}
          <p className="mt-3 break-words rounded bg-ink-50 px-3 py-2 font-mono text-xs text-ink-500">
            {error.message || 'Unknown error'}
          </p>
          <div className="mt-5 flex gap-2">
            <Button onClick={this.reset}>Try again</Button>
            <Button variant="secondary" onClick={() => window.location.assign('/')}>
              Back to dashboard
            </Button>
          </div>
        </div>
      </div>
    );
  }
}

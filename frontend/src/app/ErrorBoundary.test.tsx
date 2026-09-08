import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ErrorBoundary } from './ErrorBoundary';

/**
 * React logs every caught error to `console.error` as well as handing it to the boundary.
 * That is correct behaviour and not what is under test, so it is silenced per-test rather
 * than globally — a suite that mutes the console everywhere hides the next real warning.
 */
function silenceReactErrorLogging() {
  return vi.spyOn(console, 'error').mockImplementation(() => {});
}

function Boom({ message }: { message: string }): never {
  throw new Error(message);
}

describe('ErrorBoundary', () => {
  it('catches a rendering exception and shows a recovery UI instead of nothing', () => {
    const consoleError = silenceReactErrorLogging();

    render(
      <ErrorBoundary>
        <Boom message="quantity.replace is not a function" />
      </ErrorBoundary>,
    );

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong');
    // The message survives — it is usually the only clue support gets.
    expect(alert).toHaveTextContent('quantity.replace is not a function');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();

    consoleError.mockRestore();
  });

  it('reports the error rather than swallowing it', () => {
    const consoleError = silenceReactErrorLogging();
    const onError = vi.fn();

    render(
      <ErrorBoundary onError={onError}>
        <Boom message="boom" />
      </ErrorBoundary>,
    );

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0]![0]).toBeInstanceOf(Error);
    expect((onError.mock.calls[0]![0] as Error).message).toBe('boom');

    consoleError.mockRestore();
  });

  it('renders children again after "Try again" when the cause has cleared', async () => {
    const consoleError = silenceReactErrorLogging();

    // Throws until the underlying cause is cleared, which is what the recovery button is
    // for: a transient failure the user can retry past, not a permanently broken page.
    // The flag is flipped explicitly rather than counting renders, because React itself
    // re-renders a failing subtree once before handing it to the boundary.
    let broken = true;
    function Flaky() {
      if (broken) throw new Error('transient');
      return <p>Recovered</p>;
    }

    render(
      <ErrorBoundary>
        <Flaky />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('transient');

    broken = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Recovered')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    consoleError.mockRestore();
  });

  it('is invisible when nothing throws', () => {
    render(<ErrorBoundary><p>All well</p></ErrorBoundary>);

    expect(screen.getByText('All well')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

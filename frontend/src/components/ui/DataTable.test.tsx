import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DataTable } from './DataTable';
import type { Column } from './DataTable';

/**
 * A clickable row that only a mouse can reach is not a feature, it is an exclusion.
 *
 * The desktop table used to be exactly that: `<tr onClick>` with no tabindex, no key
 * handling and no focus ring. Every list in the application — matters, clients, invoices —
 * could be read with the keyboard and opened with none of them.
 */

interface Matter { id: string; number: string; title: string }

const ROWS: Matter[] = [
  { id: '1', number: 'CASE-1', title: 'Menon v. Iyer' },
  { id: '2', number: 'CASE-2', title: 'Rao & Co' },
];

const COLUMNS: Column<Matter>[] = [
  { key: 'number', header: 'Number', cell: (row) => row.number, primary: true },
  { key: 'title', header: 'Title', cell: (row) => row.title },
];

function mount(onRowClick?: (row: Matter) => void, columns: Column<Matter>[] = COLUMNS) {
  return render(
    <DataTable rows={ROWS} columns={columns} rowKey={(row) => row.id}
      onRowClick={onRowClick} caption="Matters" />,
  );
}

/** The desktop table; the component also renders a separate list for narrow screens. */
function desktopRows() {
  return within(screen.getByRole('grid')).getAllByRole('row').slice(1);
}

describe('DataTable — keyboard access to clickable rows', () => {
  it('lets a keyboard user tab to a row and open it with Enter', async () => {
    const onRowClick = vi.fn();
    mount(onRowClick);

    await userEvent.tab();
    const [first] = desktopRows();
    expect(first).toHaveFocus();

    await userEvent.keyboard('{Enter}');
    expect(onRowClick).toHaveBeenCalledWith(ROWS[0]);
  });

  it('opens a row with Space as well', async () => {
    const onRowClick = vi.fn();
    mount(onRowClick);

    desktopRows()[1]!.focus();
    await userEvent.keyboard(' ');

    expect(onRowClick).toHaveBeenCalledWith(ROWS[1]);
  });

  it('puts every row in the tab order', () => {
    mount(vi.fn());
    for (const row of desktopRows()) {
      expect(row).toHaveAttribute('tabindex', '0');
    }
  });

  it('calls the table a grid only when its rows do something', () => {
    // A read-only table is a table. Announcing it as an interactive grid would promise
    // assistive technology behaviour that is not there.
    const { unmount } = mount(vi.fn());
    expect(screen.getByRole('grid')).toBeInTheDocument();
    unmount();

    mount(undefined);
    expect(screen.queryByRole('grid')).not.toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
  });

  it('leaves a read-only table out of the tab order entirely', () => {
    mount(undefined);
    for (const row of within(screen.getByRole('table')).getAllByRole('row').slice(1)) {
      expect(row).not.toHaveAttribute('tabindex');
    }
  });

  it('carries a visible focus ring', () => {
    mount(vi.fn());
    // Not a rendering assertion for its own sake: without this a keyboard user can move
    // through a list of twenty matters with no idea which one they are on.
    expect(desktopRows()[0]!.className).toContain('focus-visible:ring-2');
  });
});

describe('DataTable — rows that contain their own controls', () => {
  const withAction: Column<Matter>[] = [
    ...COLUMNS,
    {
      key: 'actions',
      header: 'Actions',
      desktopOnly: true,
      cell: (row) => <button type="button" onClick={() => deleted.push(row.id)}>Delete</button>,
    },
  ];
  const deleted: string[] = [];

  it('does not open the row when a button inside it is used', async () => {
    const onRowClick = vi.fn();
    mount(onRowClick, withAction);

    await userEvent.click(within(desktopRows()[0]!).getByRole('button', { name: 'Delete' }));

    expect(deleted).toEqual(['1']);
    // The click reaches the button and stops there — the row's own action is not also
    // fired, which would open a record the user was trying to delete.
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('does not open the row when a nested button is activated by keyboard', async () => {
    const onRowClick = vi.fn();
    mount(onRowClick, withAction);

    within(desktopRows()[1]!).getByRole('button', { name: 'Delete' }).focus();
    await userEvent.keyboard('{Enter}');

    expect(onRowClick).not.toHaveBeenCalled();
  });
});

describe('DataTable — the narrow-screen list', () => {
  it('keeps its existing button semantics and keyboard behaviour', async () => {
    const onRowClick = vi.fn();
    mount(onRowClick);

    // The mobile rows are the only role="button" elements the component renders.
    const rows = screen.getAllByRole('button');
    expect(rows).toHaveLength(ROWS.length);

    rows[0]!.focus();
    await userEvent.keyboard('{Enter}');
    expect(onRowClick).toHaveBeenCalledWith(ROWS[0]);
  });

  it('still responds to a plain click on the row body', async () => {
    const onRowClick = vi.fn();
    mount(onRowClick);

    await userEvent.click(screen.getAllByRole('button')[1]!);

    expect(onRowClick).toHaveBeenCalledWith(ROWS[1]);
  });
});

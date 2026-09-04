import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Tabs } from './Tabs';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'timeline', label: 'Timeline' },
  { id: 'hearings', label: 'Hearings' },
] as const;

type Id = (typeof TABS)[number]['id'];

function setup(active: Id = 'overview') {
  const onChange = vi.fn();
  render(<Tabs tabs={TABS} active={active} onChange={onChange} panelId="panel" />);
  return { onChange };
}

/**
 * The keyboard model is the reason `Tabs` exists as a component rather than a row of
 * buttons, so it is the part worth testing: a row of plain buttons looks identical and
 * makes a keyboard user press Tab three times to reach the panel.
 */
describe('Tabs', () => {
  it('puts only the selected tab in the tab order', () => {
    setup('timeline');

    expect(screen.getByRole('tab', { name: 'Timeline' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('tabindex', '-1');
    expect(screen.getByRole('tab', { name: 'Hearings' })).toHaveAttribute('tabindex', '-1');
  });

  it('marks exactly one tab selected and points every tab at the panel', () => {
    setup('hearings');

    const selected = screen.getAllByRole('tab').filter(
      (tab) => tab.getAttribute('aria-selected') === 'true',
    );
    expect(selected).toHaveLength(1);
    expect(selected[0]).toHaveAccessibleName('Hearings');
    for (const tab of screen.getAllByRole('tab')) {
      expect(tab).toHaveAttribute('aria-controls', 'panel');
    }
  });

  it('moves between tabs with the arrow keys and wraps at the ends', async () => {
    const { onChange } = setup('overview');
    // The strip is controlled, so `active` stays put: each press is measured from the
    // same starting tab, which is what makes the wrap assertion meaningful.
    screen.getByRole('tab', { name: 'Overview' }).focus();

    await userEvent.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('timeline');

    // Wrapping backwards from the first tab lands on the last, not nowhere.
    await userEvent.keyboard('{ArrowLeft}');
    expect(onChange).toHaveBeenLastCalledWith('hearings');
  });

  it('jumps to the first and last tab with Home and End', async () => {
    const { onChange } = setup('timeline');
    screen.getByRole('tab', { name: 'Timeline' }).focus();

    await userEvent.keyboard('{End}');
    expect(onChange).toHaveBeenLastCalledWith('hearings');

    await userEvent.keyboard('{Home}');
    expect(onChange).toHaveBeenLastCalledWith('overview');
  });

  it('selects a tab on click', async () => {
    const { onChange } = setup();
    await userEvent.click(screen.getByRole('tab', { name: 'Hearings' }));
    expect(onChange).toHaveBeenCalledWith('hearings');
  });
});

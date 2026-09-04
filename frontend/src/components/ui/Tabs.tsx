import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Icon } from './icons';
import type { IconName } from './icons';

export interface TabDefinition<T extends string> {
  id: T;
  label: string;
  icon?: IconName;
  /** A count shown beside the label, when one is known. */
  count?: number;
}

/**
 * A tab strip that behaves the way the ARIA authoring practices describe.
 *
 * The part worth having a component for is the keyboard model: only the selected tab is
 * in the tab order, and left/right/Home/End move between them. A row of plain buttons
 * looks identical and makes a keyboard user press Tab seven times to reach the panel.
 */
export function Tabs<T extends string>({ tabs, active, onChange, panelId }: {
  tabs: readonly TabDefinition<T>[];
  active: T;
  onChange: (id: T) => void;
  panelId?: string;
}) {
  const listRef = useRef<HTMLDivElement>(null);

  // On a narrow screen the strip scrolls, and a tab selected from the URL can sit off
  // the right edge with nothing to say the page is showing it.
  useEffect(() => {
    listRef.current
      ?.querySelector<HTMLElement>(`[data-tab="${active}"]`)
      ?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [active]);

  const move = (delta: number) => {
    const index = tabs.findIndex((tab) => tab.id === active);
    if (index === -1) return;
    const nextTab = tabs[(index + delta + tabs.length) % tabs.length];
    if (!nextTab) return;
    onChange(nextTab.id);
    // Focus follows selection, which is the automatic-activation pattern; the panels here
    // are already mounted, so there is no cost to selecting as you arrow across.
    listRef.current?.querySelector<HTMLButtonElement>(`[data-tab="${nextTab.id}"]`)?.focus();
  };

  const first = tabs[0];
  const last = tabs[tabs.length - 1];

  return (
    <div className="scroll-x -mx-3 border-b border-ink-200 px-3 sm:mx-0 sm:px-0">
      <div
        ref={listRef}
        role="tablist"
        aria-label="Sections"
        className="flex min-w-max gap-0.5"
        onKeyDown={(event) => {
          if (event.key === 'ArrowRight') { event.preventDefault(); move(1); }
          if (event.key === 'ArrowLeft') { event.preventDefault(); move(-1); }
          if (event.key === 'Home' && first) { event.preventDefault(); onChange(first.id); }
          if (event.key === 'End' && last) { event.preventDefault(); onChange(last.id); }
        }}
      >
        {tabs.map((tab) => {
          const selected = tab.id === active;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              data-tab={tab.id}
              id={`tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={panelId}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(tab.id)}
              className={cn(
                'inline-flex items-center gap-1.5 whitespace-nowrap border-b-2 px-3 py-2.5',
                'text-sm font-medium transition-colors',
                selected
                  ? 'border-brand-600 text-brand-700'
                  : 'border-transparent text-ink-600 hover:border-ink-300 hover:text-ink-900',
              )}
            >
              {tab.icon && <Icon name={tab.icon} className="h-4 w-4" />}
              {tab.label}
              {tab.count !== undefined && (
                <span className={cn(
                  'rounded-full px-1.5 py-0.5 text-2xs font-semibold',
                  selected ? 'bg-brand-100 text-brand-800' : 'bg-ink-100 text-ink-600',
                )}>
                  {tab.count}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

export function TabPanel({ id, labelledBy, children }: {
  id: string; labelledBy: string; children: ReactNode;
}) {
  return (
    <div id={id} role="tabpanel" aria-labelledby={labelledBy} tabIndex={-1} className="mt-4">
      {children}
    </div>
  );
}

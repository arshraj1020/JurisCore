import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button, Field, Input, PasswordInput, Toggle } from './primitives';

describe('Button', () => {
  /**
   * The default matters: a button inside a form that was meant to open a dialog and
   * instead submits it is easy to ship and hard to spot.
   */
  it('is a plain button unless it says otherwise', () => {
    render(<Button>Open</Button>);
    expect(screen.getByRole('button', { name: 'Open' })).toHaveAttribute('type', 'button');
  });

  it('disables itself while loading, so a double click cannot submit twice', async () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>Save</Button>);

    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');

    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('Field', () => {
  it('wires the label, the error and aria-invalid to the control', () => {
    render(
      <Field label="Matter title" error="Enter a title" required>
        {({ id, describedBy, invalid }) => (
          <Input id={id} aria-describedby={describedBy} invalid={invalid} />
        )}
      </Field>,
    );

    const input = screen.getByLabelText(/Matter title/);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    // The message is announced because it is both the described-by target and an alert.
    const message = screen.getByRole('alert');
    expect(message).toHaveTextContent('Enter a title');
    expect(input.getAttribute('aria-describedby')).toBe(message.id);
  });

  it('describes the control by its hint when there is no error', () => {
    render(
      <Field label="Currency" hint="Leave blank for the firm default.">
        {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} />}
      </Field>,
    );

    const input = screen.getByLabelText('Currency');
    const hintId = input.getAttribute('aria-describedby');
    expect(hintId).toBeTruthy();
    expect(document.getElementById(hintId as string))
      .toHaveTextContent('Leave blank for the firm default.');
  });

  it('gives two fields on one page distinct ids', () => {
    render(
      <>
        <Field label="First name">{({ id }) => <Input id={id} />}</Field>
        <Field label="Last name">{({ id }) => <Input id={id} />}</Field>
      </>,
    );

    expect(screen.getByLabelText('First name').id)
      .not.toBe(screen.getByLabelText('Last name').id);
  });
});

describe('PasswordInput', () => {
  it('masks by default and reveals on request, without losing its label', async () => {
    render(
      <Field label="Password" required>
        {({ id }) => <PasswordInput id={id} defaultValue="correct horse" />}
      </Field>,
    );

    const input = screen.getByLabelText(/^Password/);
    expect(input).toHaveAttribute('type', 'password');

    await userEvent.click(screen.getByRole('button', { name: 'Reveal the password' }));
    expect(input).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: 'Hide the password' }))
      .toHaveAttribute('aria-pressed', 'true');
  });
});

describe('Toggle', () => {
  it('stays a checkbox with an accessible name', async () => {
    const onChange = vi.fn();
    render(
      <Toggle label="Invoices" description="Issued and overdue invoices."
        checked={false} onChange={onChange} />,
    );

    const control = screen.getByRole('checkbox', { name: 'Invoices' });
    expect(control).not.toBeChecked();

    await userEvent.click(control);
    expect(onChange).toHaveBeenCalledWith(true);
  });
});

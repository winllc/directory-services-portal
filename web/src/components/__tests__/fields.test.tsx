import { describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { FormDefinition, FormField } from '@dsp/shared';
import { FieldInput } from '../FieldInput';
import { EntryForm } from '../EntryForm';

const base: Omit<FormField, 'id' | 'attribute' | 'label' | 'widget' | 'multiValued'> = {
  required: false,
  readOnly: false,
  selfEditable: false,
};

function Harness({ field, initial = [] as string[] }: { field: FormField; initial?: string[] }) {
  const [values, setValues] = useState<string[]>(initial);
  return (
    <>
      <FieldInput field={field} values={values} onChange={setValues} options={field.dropdown?.type === 'static' ? field.dropdown.options : undefined} />
      <output data-testid="out">{JSON.stringify(values)}</output>
    </>
  );
}

const out = () => JSON.parse(screen.getByTestId('out').textContent ?? '[]');

describe('FieldInput', () => {
  it('free text, multi-valued: adds and removes values', async () => {
    const user = userEvent.setup();
    render(<Harness field={{ ...base, id: 'm', attribute: 'mail', label: 'Mail', widget: 'text', multiValued: true }} />);
    await user.type(screen.getByLabelText('Mail value 1'), 'a@example.com');
    await user.click(screen.getByRole('button', { name: /add value/i }));
    await user.type(screen.getByLabelText('Mail value 2'), 'b@example.com');
    expect(out()).toEqual(['a@example.com', 'b@example.com']);
    await user.click(screen.getByRole('button', { name: 'Remove Mail value 1' }));
    expect(out()).toEqual(['b@example.com']);
  });

  it('drop down, single value: selects from options', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        field={{ ...base, id: 'd', attribute: 'dept', label: 'Dept', widget: 'dropdown', multiValued: false, dropdown: { type: 'static', options: [{ value: 'eng', label: 'Engineering' }, { value: 'ops' }] } }}
      />,
    );
    await user.selectOptions(screen.getByRole('combobox'), 'eng');
    expect(out()).toEqual(['eng']);
  });

  it('drop down, multi-valued: picks options and custom values', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        field={{ ...base, id: 's', attribute: 'skill', label: 'Skills', widget: 'dropdown', multiValued: true, allowCustomValues: true, dropdown: { type: 'static', options: [{ value: 'Go' }, { value: 'Rust' }] } }}
      />,
    );
    const input = screen.getByRole('combobox');
    await user.click(input);
    await user.click(screen.getByRole('option', { name: 'Rust' }));
    await user.type(input, 'Zig{Enter}');
    expect(out()).toEqual(['Rust', 'Zig']);
    await user.click(screen.getByRole('button', { name: 'Remove Rust' }));
    expect(out()).toEqual(['Zig']);
  });
});

describe('EntryForm', () => {
  const form: FormDefinition = {
    id: 'f',
    name: 'Person',
    objectClasses: ['inetOrgPerson'],
    rdnAttribute: 'uid',
    createdAt: '',
    updatedAt: '',
    fields: [
      { ...base, id: 'uid', attribute: 'uid', label: 'Username', widget: 'text', multiValued: false, required: true },
      { ...base, id: 'mail', attribute: 'mail', label: 'E-mail', widget: 'text', multiValued: false, format: 'email' },
      { ...base, id: 'badge', attribute: 'badge', label: 'Badge', widget: 'text', multiValued: false, readOnly: true },
    ],
  };
  const wrap = (ui: React.ReactNode) => render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>);

  it('validates on the client before submitting', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    wrap(<EntryForm definitionId="d" form={form} mode="create" onSubmit={onSubmit} />);
    await user.type(screen.getByLabelText('E-mail'), 'nope');
    await user.click(screen.getByRole('button', { name: 'Create' }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText('Username is required (it names the entry)')).toBeInTheDocument();
    expect(screen.getByText('"nope" is not a valid e-mail address')).toBeInTheDocument();
  });

  it('submits only changed, writable attributes on update', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    wrap(<EntryForm definitionId="d" form={form} mode="update" entry={{ uid: ['jd'], mail: ['a@b.co'], badge: ['7'] }} onSubmit={onSubmit} />);
    expect(screen.getByLabelText('Badge')).toBeDisabled();
    await user.clear(screen.getByLabelText('E-mail'));
    await user.type(screen.getByLabelText('E-mail'), 'x@y.co');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(onSubmit).toHaveBeenCalledWith({ mail: ['x@y.co'] });
  });
});

import {
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  useId,
} from 'react';

const cx = (...classes: Array<string | false | undefined>) => classes.filter(Boolean).join(' ');

/* ---------------------------------------------------------------- Button */

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost';
  loading?: boolean;
};

export function Button({ variant = 'primary', loading = false, disabled, children, className, ...rest }: ButtonProps) {
  return (
    <button
      type="button"
      {...rest}
      className={cx('ds-button', `ds-button--${variant}`, className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
    >
      {loading && <span className="ds-button__spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}

/* ---------------------------------------------------------------- Fields */

type FieldChrome = { label: string; hint?: string; error?: string };

function useFieldIds(error?: string, hint?: string) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  return { id, hintId, errorId, describedBy: [errorId, hintId].filter(Boolean).join(' ') || undefined };
}

export function TextField({ label, hint, error, ...rest }: FieldChrome & InputHTMLAttributes<HTMLInputElement>) {
  const { id, hintId, errorId, describedBy } = useFieldIds(error, hint);
  return (
    <div className="ds-field">
      <label className="ds-field__label" htmlFor={id}>{label}</label>
      <input
        id={id}
        className="ds-field__control"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...rest}
      />
      {hint && <span id={hintId} className="ds-field__hint">{hint}</span>}
      {error && <span id={errorId} className="ds-field__error">{error}</span>}
    </div>
  );
}

type Option = { value: string; label: string };

export function Select({
  label, hint, error, options, placeholder, ...rest
}: FieldChrome & SelectHTMLAttributes<HTMLSelectElement> & { options: Option[]; placeholder?: string }) {
  const { id, hintId, errorId, describedBy } = useFieldIds(error, hint);
  return (
    <div className="ds-field">
      <label className="ds-field__label" htmlFor={id}>{label}</label>
      <select
        id={id}
        className="ds-field__control"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...rest}
      >
        {placeholder && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
      {hint && <span id={hintId} className="ds-field__hint">{hint}</span>}
      {error && <span id={errorId} className="ds-field__error">{error}</span>}
    </div>
  );
}

/* ---------------------------------------------------------------- Layout */

export function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="ds-card" aria-label={title}>
      {title && <h2 className="ds-card__title">{title}</h2>}
      {children}
    </section>
  );
}

export function Stack({
  direction = 'column', gap = 4, children,
}: { direction?: 'row' | 'column'; gap?: 1 | 2 | 3 | 4 | 6 | 8; children: ReactNode }) {
  return (
    <div className={cx('ds-stack', `ds-stack--${direction}`)} style={{ gap: `var(--ds-space-${gap})` }}>
      {children}
    </div>
  );
}

/* ---------------------------------------------------------------- Feedback */

type Tone = 'neutral' | 'success' | 'danger' | 'warning';

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={cx('ds-badge', `ds-badge--${tone}`)}>{children}</span>;
}

export function Alert({
  tone = 'info', title, children,
}: { tone?: 'info' | 'success' | 'danger' | 'warning'; title?: string; children?: ReactNode }) {
  // Erros interrompem o leitor de tela (alert); demais são anunciados educadamente (status).
  const role = tone === 'danger' ? 'alert' : 'status';
  return (
    <div className={cx('ds-alert', `ds-alert--${tone}`)} role={role}>
      {title && <div className="ds-alert__title">{title}</div>}
      {children}
    </div>
  );
}

/* ---------------------------------------------------------------- Navigation */

export function Tabs<T extends string>({
  tabs, value, onChange, label,
}: { tabs: Array<{ id: T; label: string }>; value: T; onChange: (id: T) => void; label: string }) {
  return (
    <div className="ds-tabs" role="tablist" aria-label={label}>
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          className="ds-tabs__tab"
          aria-selected={tab.id === value}
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}

/* ---------------------------------------------------------------- Data display */

const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

export function formatMoney(value: number): string {
  return brl.format(value);
}

export function Money({ value, signed = false }: { value: number; signed?: boolean }) {
  const tone = signed ? (value < 0 ? 'ds-money--negative' : 'ds-money--positive') : undefined;
  return <span className={tone}>{formatMoney(value)}</span>;
}

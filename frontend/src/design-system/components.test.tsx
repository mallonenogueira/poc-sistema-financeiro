import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Alert, Button, TextField, formatMoney } from './components';

describe('Design System', () => {
  it('Button em loading fica desabilitado e sinaliza aria-busy', async () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>Salvar</Button>);

    const button = screen.getByRole('button', { name: 'Salvar' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('TextField associa label, erro e aria-invalid para leitores de tela', () => {
    render(<TextField label="CPF" error="Obrigatório" />);

    const input = screen.getByLabelText('CPF');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Obrigatório');
  });

  it('Alert de erro usa role=alert; demais tons usam role=status', () => {
    const { rerender } = render(<Alert tone="danger">falhou</Alert>);
    expect(screen.getByRole('alert')).toHaveTextContent('falhou');

    rerender(<Alert tone="success">ok</Alert>);
    expect(screen.getByRole('status')).toHaveTextContent('ok');
  });

  it('formata moeda em pt-BR', () => {
    expect(formatMoney(1234.5).replace(/\s/g, ' ')).toBe('R$ 1.234,50');
  });
});

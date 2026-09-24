import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Account, Transfer } from '../../api/bank';
import { bankApi } from '../../api/bank';
import { ApiError } from '../../api/http';
import { TransferForm } from './TransferForm';

vi.mock('../../api/bank', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../api/bank')>();
  return { ...original, bankApi: { ...original.bankApi, transfer: vi.fn() } };
});

const accounts: Account[] = [
  { id: 'a', holderName: 'Ana', maskedDocument: '***', balance: 1000, status: 'ACTIVE', createdAt: '' },
  { id: 'b', holderName: 'Bruno', maskedDocument: '***', balance: 50, status: 'ACTIVE', createdAt: '' },
];

const completed: Transfer = {
  id: 't1', sourceAccountId: 'a', targetAccountId: 'b', amount: 100, fee: 0, type: 'PIX',
  status: 'COMPLETED', rejectionReason: null, createdAt: '',
};

async function fillForm(amount = '100') {
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText('Conta de origem'), 'a');
  await user.selectOptions(screen.getByLabelText('Conta de destino'), 'b');
  await user.type(screen.getByLabelText('Valor (R$)'), amount);
  await user.click(screen.getByRole('button', { name: 'Transferir' }));
}

describe('TransferForm', () => {
  beforeEach(() => vi.mocked(bankApi.transfer).mockReset());

  it('valida campos antes de chamar a API', async () => {
    render(<TransferForm accounts={accounts} />);

    await userEvent.click(screen.getByRole('button', { name: 'Transferir' }));

    expect(screen.getByText('Selecione a conta de origem')).toBeInTheDocument();
    expect(screen.getByText('Informe um valor maior que zero')).toBeInTheDocument();
    expect(bankApi.transfer).not.toHaveBeenCalled();
  });

  it('envia a transferência e mostra sucesso', async () => {
    vi.mocked(bankApi.transfer).mockResolvedValue(completed);
    const onCompleted = vi.fn();
    render(<TransferForm accounts={accounts} onCompleted={onCompleted} />);

    await fillForm('100,50');

    expect(bankApi.transfer).toHaveBeenCalledWith(
      { sourceAccountId: 'a', targetAccountId: 'b', amount: 100.5, type: 'PIX' },
      expect.any(String),
    );
    expect(await screen.findByText('Transferência concluída')).toBeInTheDocument();
    expect(onCompleted).toHaveBeenCalledWith(completed);
  });

  it('reutiliza a Idempotency-Key ao tentar de novo após falha', async () => {
    vi.mocked(bankApi.transfer)
      .mockRejectedValueOnce(new ApiError(503, 'FraudServiceUnavailable', 'Tente novamente'))
      .mockResolvedValueOnce(completed);
    render(<TransferForm accounts={accounts} />);

    await fillForm();
    expect(await screen.findByRole('alert')).toHaveTextContent('Tente novamente');
    await userEvent.click(screen.getByRole('button', { name: 'Transferir' }));
    await screen.findByText('Transferência concluída');

    const [first, second] = vi.mocked(bankApi.transfer).mock.calls;
    expect(second[1]).toBe(first[1]);
  });

  it('exibe o motivo quando o antifraude recusa', async () => {
    vi.mocked(bankApi.transfer).mockResolvedValue({
      ...completed, status: 'REJECTED', rejectionReason: 'Valor acima do padrão',
    });
    render(<TransferForm accounts={accounts} />);

    await fillForm('50000');

    expect(await screen.findByText('Valor acima do padrão')).toBeInTheDocument();
  });
});

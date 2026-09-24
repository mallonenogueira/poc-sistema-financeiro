import { type FormEvent, useState } from 'react';
import { type Account, bankApi } from '../../api/bank';
import { ApiError } from '../../api/http';
import { Alert, Badge, Button, Card, Money, Stack, TextField } from '../../design-system';

type Props = { accounts?: Account[]; loading: boolean; error?: Error; onChanged: () => void };

export function AccountsPage({ accounts, loading, error, onChanged }: Props) {
  const [holderName, setHolderName] = useState('');
  const [document, setDocument] = useState('');
  const [deposit, setDeposit] = useState('');
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string>();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setFailure(undefined);
    try {
      await bankApi.openAccount({ holderName, document, initialDeposit: Number(deposit || 0) });
      setHolderName('');
      setDocument('');
      setDeposit('');
      onChanged();
    } catch (e) {
      setFailure(e instanceof ApiError ? e.message : 'Falha ao abrir conta');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Stack gap={6}>
      <Card title="Contas">
        {loading && <p>Carregando...</p>}
        {error && <Alert tone="danger" title="Erro ao carregar contas">{error.message}</Alert>}
        {accounts && (
          <div className="ds-table-wrapper">
            <table className="ds-table">
              <thead>
                <tr><th>Titular</th><th>CPF</th><th>Saldo</th><th>Status</th></tr>
              </thead>
              <tbody>
                {accounts.map((account) => (
                  <tr key={account.id}>
                    <td>{account.holderName}</td>
                    <td>{account.maskedDocument}</td>
                    <td><Money value={account.balance} /></td>
                    <td>
                      <Badge tone={account.status === 'ACTIVE' ? 'success' : 'danger'}>{account.status}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="Abrir conta">
        <form onSubmit={submit}>
          <Stack gap={4}>
            <TextField label="Nome do titular" required value={holderName}
                       onChange={(e) => setHolderName(e.target.value)} />
            <TextField label="CPF" hint="Somente números (11 dígitos)" required inputMode="numeric"
                       pattern="\d{11}" maxLength={11} value={document}
                       onChange={(e) => setDocument(e.target.value.replace(/\D/g, ''))} />
            <TextField label="Depósito inicial (R$)" inputMode="decimal" value={deposit}
                       onChange={(e) => setDeposit(e.target.value)} />
            <div><Button type="submit" loading={saving}>Abrir conta</Button></div>
            {failure && <Alert tone="danger">{failure}</Alert>}
          </Stack>
        </form>
      </Card>
    </Stack>
  );
}

import { useEffect, useState } from 'react';
import { type Account, type ExportResult, type StatementEntry, bankApi } from '../../api/bank';
import { useAsync } from '../../hooks/useAsync';
import { Alert, Badge, Button, Card, Money, Select, Stack } from '../../design-system';

export function StatementPage({ accounts }: { accounts: Account[] }) {
  const [accountId, setAccountId] = useState(accounts[0]?.id ?? '');
  const [exported, setExported] = useState<ExportResult>();
  const [exporting, setExporting] = useState(false);
  const statement = useAsync(() => (accountId ? bankApi.statement(accountId) : Promise.resolve([])), [accountId]);
  const [live, setLive] = useState<StatementEntry[]>([]);

  // Assinatura SSE: lançamentos novos aparecem sem recarregar a página.
  useEffect(() => {
    setLive([]);
    if (!accountId) return;
    return bankApi.subscribeStatement(accountId, (entry) =>
      setLive((current) => [entry, ...current.filter((e) => e.id !== entry.id)]));
  }, [accountId]);

  const persisted = statement.data ?? [];
  const entries = [...live, ...persisted.filter((p) => !live.some((l) => l.id === p.id))];

  async function exportCsv() {
    setExporting(true);
    try {
      setExported(await bankApi.exportStatement(accountId));
    } finally {
      setExporting(false);
    }
  }

  const name = (id: string) => accounts.find((a) => a.id === id)?.holderName ?? `${id.slice(0, 8)}…`;

  return (
    <Card title="Extrato">
      <Stack gap={4}>
        <Stack direction="row" gap={4}>
          <Select label="Conta" value={accountId} onChange={(e) => setAccountId(e.target.value)}
                  options={accounts.map((a) => ({ value: a.id, label: a.holderName }))} />
          <Button variant="secondary" onClick={exportCsv} loading={exporting} disabled={!accountId}>
            Exportar CSV (S3)
          </Button>
        </Stack>

        {exported && (
          <Alert tone="info" title="Exportação enviada">
            s3://{exported.bucket}/{exported.key} — a Lambda gera o resumo em <code>reports/</code>.
          </Alert>
        )}
        {statement.error && <Alert tone="danger" title="Erro ao carregar extrato">{statement.error.message}</Alert>}
        {!statement.loading && entries.length === 0 && <p>Nenhum lançamento.</p>}

        {entries.length > 0 && (
          <div className="ds-table-wrapper">
            <table className="ds-table">
              <thead>
                <tr><th>Data</th><th>Descrição</th><th>Tipo</th><th>Valor</th></tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.id}>
                    <td>{new Date(entry.occurredAt).toLocaleString('pt-BR')}</td>
                    <td>
                      {entry.direction === 'DEBIT' ? 'Enviado para ' : 'Recebido de '}
                      {name(entry.counterpartyAccountId)}
                      {live.some((l) => l.id === entry.id) && <> <Badge tone="warning">novo</Badge></>}
                    </td>
                    <td><Badge>{entry.type}</Badge></td>
                    <td><Money value={entry.direction === 'DEBIT' ? -entry.amount : entry.amount} signed /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Stack>
    </Card>
  );
}

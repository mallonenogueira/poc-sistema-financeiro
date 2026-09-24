import { useState } from 'react';
import { bankApi } from './api/bank';
import { Card, Tabs } from './design-system';
import { AccountsPage } from './features/accounts/AccountsPage';
import { DesignSystemPage } from './features/design-system/DesignSystemPage';
import { StatementPage } from './features/statements/StatementPage';
import { TransferForm } from './features/transfers/TransferForm';
import { useAsync } from './hooks/useAsync';
import './app.css';

type Section = 'accounts' | 'transfer' | 'statement' | 'design-system';

const SECTIONS: Array<{ id: Section; label: string }> = [
  { id: 'accounts', label: 'Contas' },
  { id: 'transfer', label: 'Transferir' },
  { id: 'statement', label: 'Extrato' },
  { id: 'design-system', label: 'Design System' },
];

export function App() {
  const [section, setSection] = useState<Section>('accounts');
  const accounts = useAsync(bankApi.listAccounts, []);

  return (
    <div className="app">
      <header className="app__header">
        <h1 className="app__brand">PocBank</h1>
        <Tabs label="Seções" tabs={SECTIONS} value={section} onChange={setSection} />
      </header>

      <main className="app__content">
        {section === 'accounts' && (
          <AccountsPage accounts={accounts.data} loading={accounts.loading} error={accounts.error}
                        onChanged={accounts.reload} />
        )}
        {section === 'transfer' && (
          <Card title="Nova transferência">
            <TransferForm accounts={accounts.data ?? []} onCompleted={accounts.reload} />
          </Card>
        )}
        {section === 'statement' && accounts.data && <StatementPage accounts={accounts.data} />}
        {section === 'design-system' && <DesignSystemPage />}
      </main>
    </div>
  );
}

import { http } from './http';

export type Account = {
  id: string;
  holderName: string;
  maskedDocument: string;
  balance: number;
  status: 'ACTIVE' | 'BLOCKED';
  createdAt: string;
};

export type TransferType = 'PIX' | 'TED';

export type TransferRequest = {
  sourceAccountId: string;
  targetAccountId: string;
  amount: number;
  type: TransferType;
};

export type Transfer = TransferRequest & {
  id: string;
  fee: number;
  status: 'COMPLETED' | 'REJECTED';
  rejectionReason: string | null;
  createdAt: string;
};

export type StatementEntry = {
  id: string;
  accountId: string;
  transferId: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: number;
  fee: number;
  counterpartyAccountId: string;
  type: TransferType;
  occurredAt: string;
};

export type ExportResult = { bucket: string; key: string; entries: number };

export const bankApi = {
  listAccounts: () => http<Account[]>('/api/accounts'),

  openAccount: (body: { holderName: string; document: string; initialDeposit: number }) =>
    http<Account>('/api/accounts', { method: 'POST', body: JSON.stringify(body) }),

  /** A mesma idempotencyKey deve ser reenviada em retries do mesmo pedido. */
  transfer: (body: TransferRequest, idempotencyKey: string) =>
    http<Transfer>('/api/transfers', {
      method: 'POST',
      body: JSON.stringify(body),
      headers: { 'Idempotency-Key': idempotencyKey },
    }),

  statement: (accountId: string) => http<StatementEntry[]>(`/api/statements/${accountId}`),

  exportStatement: (accountId: string) =>
    http<ExportResult>(`/api/statements/${accountId}/exports`, { method: 'POST' }),

  /** Server-Sent Events: devolve a função de cancelamento da assinatura. */
  subscribeStatement: (accountId: string, onEntry: (entry: StatementEntry) => void) => {
    const source = new EventSource(`/api/statements/${accountId}/stream`);
    source.addEventListener('statement-entry', (event) => onEntry(JSON.parse((event as MessageEvent).data)));
    return () => source.close();
  },
};

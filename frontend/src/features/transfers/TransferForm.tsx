import { type FormEvent, useRef, useState } from 'react';
import { type Account, type Transfer, type TransferType, bankApi } from '../../api/bank';
import { ApiError } from '../../api/http';
import { Alert, Badge, Button, Money, Select, Stack, TextField, formatMoney } from '../../design-system';

type Props = { accounts: Account[]; onCompleted?: (transfer: Transfer) => void };

type Errors = Partial<Record<'source' | 'target' | 'amount', string>>;

export function TransferForm({ accounts, onCompleted }: Props) {
  const [source, setSource] = useState('');
  const [target, setTarget] = useState('');
  const [amount, setAmount] = useState('');
  const [type, setType] = useState<TransferType>('PIX');
  const [errors, setErrors] = useState<Errors>({});
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<Transfer>();
  const [failure, setFailure] = useState<string>();

  // Mantida entre tentativas do MESMO pedido: um retry após timeout não duplica a transferência.
  const idempotencyKey = useRef<string>();

  const options = accounts.map((a) => ({ value: a.id, label: `${a.holderName} — ${formatMoney(a.balance)}` }));

  function validate(): Errors {
    const next: Errors = {};
    const value = Number(amount.replace(',', '.'));
    if (!source) next.source = 'Selecione a conta de origem';
    if (!target) next.target = 'Selecione a conta de destino';
    if (source && source === target) next.target = 'Origem e destino devem ser diferentes';
    if (!amount || Number.isNaN(value) || value <= 0) next.amount = 'Informe um valor maior que zero';
    return next;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const validation = validate();
    setErrors(validation);
    if (Object.keys(validation).length > 0) return;

    idempotencyKey.current ??= crypto.randomUUID();
    setSubmitting(true);
    setFailure(undefined);
    setResult(undefined);
    try {
      const transfer = await bankApi.transfer(
        { sourceAccountId: source, targetAccountId: target, amount: Number(amount.replace(',', '.')), type },
        idempotencyKey.current,
      );
      setResult(transfer);
      idempotencyKey.current = undefined; // pedido concluído: o próximo é uma nova transferência
      onCompleted?.(transfer);
    } catch (error) {
      setFailure(error instanceof ApiError ? error.message : 'Falha de comunicação. Tente novamente.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <Stack gap={4}>
        <Select label="Conta de origem" placeholder="Selecione..." options={options} value={source}
                onChange={(e) => setSource(e.target.value)} error={errors.source} />
        <Select label="Conta de destino" placeholder="Selecione..." options={options} value={target}
                onChange={(e) => setTarget(e.target.value)} error={errors.target} />
        <Stack direction="row" gap={4}>
          <TextField label="Valor (R$)" inputMode="decimal" value={amount}
                     onChange={(e) => setAmount(e.target.value)} error={errors.amount} />
          <Select label="Modalidade" value={type} onChange={(e) => setType(e.target.value as TransferType)}
                  options={[{ value: 'PIX', label: 'PIX (isento)' }, { value: 'TED', label: 'TED (tarifada)' }]} />
        </Stack>
        <div>
          <Button type="submit" loading={submitting}>Transferir</Button>
        </div>

        {failure && <Alert tone="danger" title="Não foi possível transferir">{failure}</Alert>}
        {result?.status === 'COMPLETED' && (
          <Alert tone="success" title="Transferência concluída">
            <Money value={result.amount} /> enviados via {result.type}. Tarifa: <Money value={result.fee} />.{' '}
            <Badge tone="success">{result.status}</Badge>
          </Alert>
        )}
        {result?.status === 'REJECTED' && (
          <Alert tone="warning" title="Transferência recusada pela análise de segurança">
            {result.rejectionReason}
          </Alert>
        )}
      </Stack>
    </form>
  );
}

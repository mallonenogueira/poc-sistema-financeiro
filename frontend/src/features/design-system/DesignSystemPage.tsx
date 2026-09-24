import { useState } from 'react';
import { Alert, Badge, Button, Card, Money, Select, Stack, Tabs, TextField } from '../../design-system';

/** Catálogo vivo dos componentes (papel que o Storybook cumpriria num DS corporativo). */
export function DesignSystemPage() {
  const [tab, setTab] = useState<'a' | 'b'>('a');
  return (
    <Stack gap={6}>
      <Card title="Botões">
        <Stack direction="row" gap={3}>
          <Button>Primário</Button>
          <Button variant="secondary">Secundário</Button>
          <Button variant="ghost">Ghost</Button>
          <Button loading>Carregando</Button>
          <Button disabled>Desabilitado</Button>
        </Stack>
      </Card>
      <Card title="Campos">
        <Stack gap={4}>
          <TextField label="Com dica" hint="Texto de apoio" />
          <TextField label="Com erro" error="Campo obrigatório" defaultValue="" />
          <Select label="Seleção" options={[{ value: '1', label: 'Opção 1' }, { value: '2', label: 'Opção 2' }]} />
        </Stack>
      </Card>
      <Card title="Feedback">
        <Stack gap={3}>
          <Stack direction="row" gap={2}>
            <Badge>neutral</Badge><Badge tone="success">success</Badge>
            <Badge tone="warning">warning</Badge><Badge tone="danger">danger</Badge>
          </Stack>
          <Alert tone="info" title="Info">Mensagem informativa.</Alert>
          <Alert tone="success" title="Sucesso">Operação concluída.</Alert>
          <Alert tone="warning" title="Atenção">Verifique os dados.</Alert>
          <Alert tone="danger" title="Erro">Algo deu errado.</Alert>
        </Stack>
      </Card>
      <Card title="Dados">
        <Stack direction="row" gap={4}>
          <Money value={1234.5} /> <Money value={-99.9} signed /> <Money value={10} signed />
        </Stack>
      </Card>
      <Card title="Navegação">
        <Tabs label="Exemplo" value={tab} onChange={setTab}
              tabs={[{ id: 'a', label: 'Aba A' }, { id: 'b', label: 'Aba B' }]} />
      </Card>
    </Stack>
  );
}

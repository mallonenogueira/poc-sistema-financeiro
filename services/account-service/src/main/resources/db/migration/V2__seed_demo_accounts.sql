-- Contas de demonstração (IDs fixos facilitam scripts de teste e o README).
INSERT INTO accounts (id, holder_name, document, balance, status, created_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'Ana Souza', '12345678901', 5000.00, 'ACTIVE', now()),
       ('22222222-2222-2222-2222-222222222222', 'Bruno Lima', '98765432100', 1500.00, 'ACTIVE', now());

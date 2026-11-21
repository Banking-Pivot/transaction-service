-- Transaction Service keeps what was asked and how far it got, never a balance: the money is
-- in Account Service and Credit Service. Money is NUMERIC(19, 2). Instants are TIMESTAMPTZ and
-- the application writes them in UTC. Enum columns are VARCHAR with CHECK constraints.

-- One row per transaction. The ID is the operationId of the client, so the primary key is also
-- what makes a repeated request find the first one.
CREATE TABLE transactions (
    id                        UUID           NOT NULL,
    transaction_type          VARCHAR(20)    NOT NULL,
    status                    VARCHAR(20)    NOT NULL,
    account_id                UUID           NOT NULL,
    credit_id                 UUID           NOT NULL,
    amount                    NUMERIC(19, 2) NOT NULL,
    currency                  VARCHAR(3)     NOT NULL,
    description               VARCHAR(140),
    -- operationId the reversal is recorded under in Account Service. The debit and the payment
    -- use the ID of the transaction.
    reversal_operation_id     UUID           NOT NULL,
    fee                       NUMERIC(19, 2),
    account_balance_after     NUMERIC(19, 2),
    debt_after                NUMERIC(19, 2),
    rejection_step            VARCHAR(20),
    rejection_code            VARCHAR(60),
    rejection_message         VARCHAR(300),
    reversal_failure_code     VARCHAR(60),
    reversal_failure_message  VARCHAR(300),
    -- Correlation identifier of the request, written in every message of the transaction.
    correlation_id            VARCHAR(64)    NOT NULL,
    -- When the command of the current step was last sent and how many times it was repeated.
    command_sent_at           TIMESTAMPTZ    NOT NULL,
    command_attempts          INTEGER        NOT NULL,
    created_at                TIMESTAMPTZ    NOT NULL,
    updated_at                TIMESTAMPTZ    NOT NULL,
    version                   BIGINT         NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT uk_transactions_reversal_operation UNIQUE (reversal_operation_id),
    CONSTRAINT ck_transactions_type CHECK (transaction_type IN ('CREDIT_PAYMENT')),
    CONSTRAINT ck_transactions_status CHECK (
        status IN ('PENDING_DEBIT', 'PENDING_PAYMENT', 'PENDING_REVERSAL',
                   'COMPLETED', 'REJECTED', 'REVERSED', 'REVERSAL_FAILED')),
    CONSTRAINT ck_transactions_currency CHECK (currency IN ('PEN', 'USD')),
    CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_transactions_rejection_step CHECK (rejection_step IN ('DEBIT', 'PAYMENT')),
    CONSTRAINT ck_transactions_rejection CHECK (
        (rejection_step IS NULL AND rejection_code IS NULL AND rejection_message IS NULL)
        OR (rejection_step IS NOT NULL AND rejection_code IS NOT NULL AND rejection_message IS NOT NULL)),
    -- A rejection goes with the statuses that follow one, and only with them.
    CONSTRAINT ck_transactions_rejected CHECK (
        (rejection_step IS NOT NULL)
        = (status IN ('REJECTED', 'PENDING_REVERSAL', 'REVERSED', 'REVERSAL_FAILED'))),
    CONSTRAINT ck_transactions_reversal_failure CHECK (
        (reversal_failure_code IS NOT NULL) = (status = 'REVERSAL_FAILED')
        AND (reversal_failure_code IS NULL) = (reversal_failure_message IS NULL)),
    CONSTRAINT ck_transactions_completed CHECK (status <> 'COMPLETED' OR debt_after IS NOT NULL),
    CONSTRAINT ck_transactions_counters CHECK (command_attempts >= 0 AND version >= 0)
);

CREATE INDEX ix_transactions_account ON transactions (account_id);
CREATE INDEX ix_transactions_credit ON transactions (credit_id);
CREATE INDEX ix_transactions_created ON transactions (created_at DESC, id);

-- Transactions still waiting for a service, oldest command first: what the periodic re-send reads.
CREATE INDEX ix_transactions_waiting ON transactions (command_sent_at)
    WHERE status IN ('PENDING_DEBIT', 'PENDING_PAYMENT', 'PENDING_REVERSAL');

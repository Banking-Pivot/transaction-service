-- Two more kinds of transaction: a payment with a debit card and the movement of the funds of
-- a Yanki wallet payment. Both start by asking Card Service which accounts a card uses, so the
-- account is no longer known when the row is created, and neither kind has a credit product.
-- A wallet payment can also put money in an account. Still no balances here.

ALTER TABLE transactions
    ALTER COLUMN account_id DROP NOT NULL,
    ALTER COLUMN credit_id DROP NOT NULL,
    -- Debit card the money leaves from, and debit card the money goes to.
    ADD COLUMN source_card_id UUID,
    ADD COLUMN destination_card_id UUID,
    -- Main account of the destination card: the account that is credited.
    ADD COLUMN destination_account_id UUID,
    -- Accounts a card payment may charge, in the order it tries them: IDs separated by commas.
    -- account_id is the one being tried, or the one that was debited.
    ADD COLUMN candidate_account_ids TEXT,
    -- Movement fee the destination account paid for the credit. fee is the one of the debit.
    ADD COLUMN destination_fee NUMERIC(19, 2),
    DROP CONSTRAINT ck_transactions_type,
    DROP CONSTRAINT ck_transactions_status,
    DROP CONSTRAINT ck_transactions_rejection_step,
    DROP CONSTRAINT ck_transactions_completed;

ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_type CHECK (
        transaction_type IN ('CREDIT_PAYMENT', 'CARD_PAYMENT', 'WALLET_PAYMENT')),
    ADD CONSTRAINT ck_transactions_status CHECK (
        status IN ('PENDING_CARD', 'PENDING_DEBIT', 'PENDING_CREDIT', 'PENDING_PAYMENT',
                   'PENDING_REVERSAL', 'COMPLETED', 'REJECTED', 'REVERSED', 'REVERSAL_FAILED')),
    ADD CONSTRAINT ck_transactions_rejection_step CHECK (
        rejection_step IN ('CARD', 'DEBIT', 'CREDIT', 'PAYMENT')),
    ADD CONSTRAINT ck_transactions_completed CHECK (
        status <> 'COMPLETED' OR transaction_type <> 'CREDIT_PAYMENT' OR debt_after IS NOT NULL),
    -- What each kind of transaction is asked with.
    ADD CONSTRAINT ck_transactions_parties CHECK (
        (transaction_type = 'CREDIT_PAYMENT' AND account_id IS NOT NULL AND credit_id IS NOT NULL
            AND source_card_id IS NULL AND destination_card_id IS NULL)
        OR (transaction_type = 'CARD_PAYMENT' AND source_card_id IS NOT NULL
            AND destination_card_id IS NULL AND credit_id IS NULL)
        OR (transaction_type = 'WALLET_PAYMENT' AND credit_id IS NULL
            AND (source_card_id IS NOT NULL OR destination_card_id IS NOT NULL))),
    -- A step that moves money on an account knows the account.
    ADD CONSTRAINT ck_transactions_accounts CHECK (
        (status NOT IN ('PENDING_DEBIT', 'PENDING_PAYMENT', 'PENDING_REVERSAL', 'REVERSED',
                        'REVERSAL_FAILED')
            OR account_id IS NOT NULL)
        AND (status <> 'PENDING_CREDIT' OR destination_account_id IS NOT NULL));

-- The movements of a debit card: completed transactions that used it, newest first.
CREATE INDEX ix_transactions_source_card ON transactions (source_card_id, updated_at DESC)
    WHERE source_card_id IS NOT NULL;
CREATE INDEX ix_transactions_destination_card ON transactions (destination_card_id, updated_at DESC)
    WHERE destination_card_id IS NOT NULL;

-- What the periodic re-send reads now has two more waiting statuses.
DROP INDEX ix_transactions_waiting;
CREATE INDEX ix_transactions_waiting ON transactions (command_sent_at)
    WHERE status IN ('PENDING_CARD', 'PENDING_DEBIT', 'PENDING_CREDIT', 'PENDING_PAYMENT',
                     'PENDING_REVERSAL');

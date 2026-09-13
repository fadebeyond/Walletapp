-- Top-ups debit this wallet, so cash entering the system still has a counterparty leg.
INSERT INTO wallets (id, user_id, balance_paise, allow_negative)
VALUES ('00000000-0000-0000-0000-000000000001', 'system:house-float', 0, true);

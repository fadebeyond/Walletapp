# Wallet & P2P Transfer — write-up

| | |
| --- | --- |
| Live API | `https://<fill-in>.onrender.com` |
| Repo | `https://github.com/<fill-in>/wallet-service` |
| Logs | Render → service → **Logs** (public link in the submission email) |
| Burst script | `./scripts/burst.sh https://<fill-in>.onrender.com` |

Java 21 / Spring Boot 3 / Postgres 16. Money is integer paise from the JSON body to the column type;
a decimal amount is a `400`, never a truncation.

## Data model

`wallets` — balance in paise, `UNIQUE (user_id)`, check constraint `balance_paise >= 0`.
`transfers` — one row per movement, carrying the idempotency key, a fingerprint of the request body
and the final status. `ledger_entries` — the two signed legs of each settled movement, so
conservation is one `sum()`. `api_tokens` — hashed bearer token to user id.

Balances are denormalised onto `wallets` on purpose: the ledger is the audit trail, the balance
column is what the conditional debit operates on. Top-ups debit a house float wallet, the only row
allowed to go negative, so money entering the system still has a counterparty leg and
`sum(balance_paise)` over all wallets is always zero.

## The simplest-correct mechanism

No overdraft is one statement — `UPDATE wallets SET balance_paise = balance_paise - :amount WHERE
id = :id AND balance_paise >= :amount`. Zero rows affected means declined. The check and the
subtraction are the same atomic write, so there is no window to read a stale balance and write it
back. Conservation falls out of running the debit, the credit, both ledger legs and the status
update in one transaction.

Deadlock freedom comes from locking both wallet rows `FOR UPDATE` in sorted id order — lower wallet
id first — before any write. A→B and B→A then queue behind each other. Worth saying that
`ORDER BY … FOR UPDATE` is **not** the fix: Postgres locks rows as the scan produces them, so the
ordering is not guaranteed to be the lock ordering. Two single-row locks issued in the order the
application chose are unambiguous.

The non-obvious part is *when* the locks are taken. The foreign keys on `transfers` mean inserting
the transfer row takes a `FOR KEY SHARE` lock on both wallets, which conflicts with the `FOR UPDATE`
taken afterwards. Claiming the idempotency key first and locking second looks fine and deadlocks
badly: 200 concurrent transfers over four wallets, **199 of 200 failed** (59 deadlocks detected, 140
statement timeouts) in 41 s. Locking first, same load: **200 of 200 in 776 ms**. So the order is
locks, then key claim, then movement.

**Rejected.** `SERIALIZABLE` — correct, but it converts contention into serialisation failures the
app must catch and retry, and the hot path here is a few wallets everyone touches; paying a retry
loop for a guarantee the conditional `UPDATE` already gives is a worse trade. Read-modify-write in
Java — the lost-update bug this exercise is looking for. Advisory or Redis locks — a second system
that has to agree with Postgres about who holds what, replacing a row lock the database already has.

## Where idempotency lives

In the database, on `UNIQUE (initiated_by, idempotency_key)`, claimed by the first statement after
the locks and committed in the **same transaction** as the debit and credit. Checking for the key in
a separate read is the TOCTOU that lets a retry storm double-debit: thirty concurrent requests all
see "no such key" and all proceed.

The claim is `INSERT … ON CONFLICT (initiated_by, idempotency_key) DO UPDATE SET idempotency_key =
EXCLUDED.idempotency_key RETURNING *`, not `DO NOTHING`. `DO NOTHING` returns nothing when a
concurrent transaction has inserted but not committed, forcing a retry loop to find a row that
exists but is not yet visible. `DO UPDATE` blocks on the winner and then returns the winner's
committed row, final status included. Whether this request won is decided by comparing the returned
id with the id it proposed.

A replay with a matching fingerprint returns the original response and status, plus
`Idempotent-Replay: true`. A mismatch is a `409`. Scoping the key per caller means one client's key
choice cannot collide with another's.

## Consistency vs availability

Consistency. One Postgres primary holds every balance and every write goes through it; when it is
unreachable the service returns `503` rather than accepting writes it cannot order.

Given up: writes stop during a failover, and every write pays a round trip to the primary's region.
Both are acceptable — the alternative, accepting a debit against a replica's view of the balance, is
exactly how a wallet goes negative or a transfer applies twice. Idempotency is what makes this
liveable: a client whose request timed out mid-failover retries the same key and gets the original
result, not a second debit.

## AI: Usage 
Used to configure test classes.
Used to add Prometheus as that is not my strong suite.
To do the Writeup correction and language.

## Cost

₹0. Render free web service plus free managed Postgres. Prometheus and Grafana run only in the local
compose stack, not in the deployment.

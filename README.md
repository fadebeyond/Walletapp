# wallet-service

A wallet service with peer-to-peer transfers. Money is integer paise everywhere, and the four
invariants — conservation, no overdraft, exactly-once transfers, race-free wallet creation — are
enforced in Postgres rather than in application code.

## Run it

```bash
docker compose up --build          # app + Postgres + Prometheus + Grafana
./scripts/burst.sh http://localhost:8080
```

The app is on `:8080`, Prometheus on `:9090`, Grafana on `:3000` (anonymous, dashboard "Wallet service").
`docker compose up` is the only command needed; Flyway creates the schema on first boot.

## API

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/auth/tokens` | Issues a bearer token for a user id. Stand-in for a real IdP. |
| `POST` | `/wallets` | Get-or-create the caller's wallet. |
| `GET` | `/wallets/{id}` | Balance. Owner only. |
| `POST` | `/wallets/{id}/topups` | Funds a wallet from the house float. Needs its own idempotency key. |
| `POST` | `/transfers` | `from`, `to`, `amount_paise`, `idempotency_key`. |
| `GET` | `/transfers/{id}` | Status. Visible to either counterparty. |
| `GET` | `/health` `/metrics` | Liveness and Prometheus scrape. |
| `GET` | `/admin/invariants` | Ledger-wide conservation check. Needs `X-Admin-Token`. |

```bash
TOKEN=$(curl -s -XPOST localhost:8080/auth/tokens -H 'content-type: application/json' \
  -d '{"user_id":"alice"}' | jq -r .token)
WALLET=$(curl -s -XPOST localhost:8080/wallets -H "Authorization: Bearer $TOKEN" | jq -r .id)
curl -s -XPOST localhost:8080/wallets/$WALLET/topups -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{"amount_paise":50000,"idempotency_key":"seed-1"}'
```

A transfer returns `201` when it settles and `422` with `status: DECLINED_INSUFFICIENT_FUNDS` when it
does not. Replays repeat the original status and body and add `Idempotent-Replay: true`.

## Deploy

`render.yaml` provisions a free Docker web service and a free managed Postgres. Point Render at the
repo, hit **Apply**, and the only variable to set by hand is nothing — `DATABASE_URL` is wired from
the database and translated to a JDBC URL at startup. Any host that can run the image works the same
way; set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` instead
if the platform does not hand out a `DATABASE_URL`.

## Observability

Logs are one JSON object per line on stdout, carrying `correlation_id` (echoed as the
`X-Correlation-Id` response header) and the domain events: `transfer.created`, `transfer.debited`,
`transfer.credited`, `transfer.declined`, `transfer.completed`, `transfer.idempotent_replay`,
`transfer.idempotency_conflict`, `wallet.created`, `wallet.reused`.

```bash
docker compose logs -f app | jq 'select(.event | startswith("transfer"))'
```

`/metrics` exposes request rate, latency histograms (p99 via `histogram_quantile`) and error rate from Actuator, plus
`wallet_transfers_initiated_total`, `wallet_transfers_completed_total`, `wallet_transfers_declined_total`,
`wallet_transfers_idempotent_replays_total`, `wallet_transfers_idempotency_conflicts_total`,
`wallet_wallets_opened_total`, `wallet_wallets_reused_total`.

## Tests

```bash
mvn test        # needs Docker; Testcontainers starts a throwaway Postgres
```

`InvariantsTest` drives the three graded races through HTTP; `ApiContractTest` covers the edge cases
around the money path — decimal amounts, self-transfers, foreign wallets, declined replays.

## AI Usage

Where the approach was directed versus where a generated design was accepted is written out in the
last section of `WRITEUP.md`.

## Write-up

`WRITEUP.md` — data model, the simplest-correct mechanism and the alternatives rejected, where
idempotency lives, the consistency call, AI disclosure, and the cost note.

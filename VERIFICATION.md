# Verification run

Recorded against the deployed instance at `https://wallet-service-ofzo.onrender.com`
(commit `d9ab4fa`), driving the public API over HTTPS. Reproduce any of it with
`./scripts/burst.sh https://wallet-service-ofzo.onrender.com`.

## The three graded races

| Probe | Method | Result |
| --- | --- | --- |
| Race-free get-or-create | 50 simultaneous `POST /wallets`, one fresh user | 1 distinct wallet id, all `200` |
| Idempotent retry storm | 30 concurrent sends of one key | 1 transfer id, all `201`, 29 with `Idempotent-Replay: true`; sender −5000, receiver +5000 |
| Same key, different body | replay with a changed amount | `409 IDEMPOTENCY_KEY_CONFLICT`, no second debit |
| Conservation under contention | 150 concurrent transfers over 4 wallets, A→B and B→A together, every 17th unaffordable | 141 settled, 9 declined, 0 server errors; total 400000 → 400000; no negative balance |

## Edge cases

| Case | Expected | Observed |
| --- | --- | --- |
| Missing / invalid bearer token | 401 | 401 |
| Read another user's wallet | 403 | 403 |
| Debit a wallet you do not own | 403 | 403 |
| Read a transfer you are not party to | 403 | 403 |
| Unknown wallet / payee / transfer | 404 | 404 |
| Malformed UUID in path | 400 | 400 |
| Self-transfer | 400 | 400 |
| Zero or negative amount | 400 | 400 |
| Decimal amount (`10.5`) | 400 | 400 |
| Missing idempotency key | 400 | 400 |
| Unknown path / wrong method | 404 / 405 | 404 / 405 |
| Insufficient funds | 422, nothing moves | `422 DECLINED_INSUFFICIENT_FUNDS`, balance unchanged at 1000 |
| Replay of a declined transfer | same decline, no retry | same `422`, same transfer id, `Idempotent-Replay: true` |
| Top-up replayed with the same key | applied once | applied once, same transfer id |

## Metrics

`GET /metrics` served unauthenticated, `200`. Counters after the run above, internally consistent:

```
wallet_transfers_initiated_total{kind="TRANSFER"}  151
wallet_transfers_completed_total{kind="TRANSFER"}  142
wallet_transfers_declined_total{reason="insufficient_funds"}  9
wallet_transfers_idempotent_replays_total  29
wallet_transfers_idempotency_conflicts_total  1
wallet_wallets_opened_total  5
wallet_wallets_reused_total  49
```

Latency is published as histogram buckets bounded to 5ms–5s, so p99 comes from
`histogram_quantile` over `http_server_requests_seconds_bucket` and stays aggregatable
across instances.

## Log threading

One request, traced by its correlation id through the money path (trimmed for width):

```
{"message":"transfer.created",  "correlation_id":"521810b1-…","transfer_id":"2d539c9a-…","amount_paise":700}
{"message":"transfer.debited",  "correlation_id":"521810b1-…","transfer_id":"2d539c9a-…","wallet_id":"fd718ec6-…"}
{"message":"transfer.credited", "correlation_id":"521810b1-…","transfer_id":"2d539c9a-…","wallet_id":"19f071ad-…"}
```

The id is taken from `X-Correlation-Id` when the client sends one, generated otherwise,
and echoed on the response.

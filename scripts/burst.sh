#!/usr/bin/env bash
# Reproduces the three invariants against a running wallet service.
# Usage: ./scripts/burst.sh [base-url]
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
GET_OR_CREATE_BURST="${GET_OR_CREATE_BURST:-50}"
IDEMPOTENCY_STORM="${IDEMPOTENCY_STORM:-30}"
CONTENTION_TRANSFERS="${CONTENTION_TRANSFERS:-200}"
PARALLELISM="${PARALLELISM:-40}"
SEED_PAISE="${SEED_PAISE:-100000}"
TRANSFER_PAISE="${TRANSFER_PAISE:-700}"

command -v jq >/dev/null || { echo "jq is required"; exit 1; }

RUN_ID="$(date +%s)-$RANDOM"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
FAILURES=0

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1 ($3)"; else fail "$1 (expected $3, got $2)"; fi; }

api() { # api METHOD PATH TOKEN [BODY]
  local method="$1" path="$2" token="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -sS -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $token" \
         -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $token"
  fi
}

issue_token() { curl -sS -X POST "$BASE_URL/auth/tokens" -H 'Content-Type: application/json' \
  -d "{\"user_id\":\"$1\"}" | jq -r .token; }

run_parallel() { xargs -P "$PARALLELISM" -d '\n' -n 1 bash -c < "$1"; }

say "Target $BASE_URL"
printf '  waiting for the service to answer'
for attempt in $(seq 1 40); do
  if curl -fsS --max-time 10 "$BASE_URL/health" >/dev/null 2>&1; then
    printf ' up\n'
    break
  fi
  printf '.'
  sleep 5
  [ "$attempt" -eq 40 ] && { printf '\n'; echo "service never became healthy at $BASE_URL"; exit 1; }
done

# ---------------------------------------------------------------- probe 1
say "Probe 1 — concurrent get-or-create ($GET_OR_CREATE_BURST simultaneous POST /wallets)"
FRESH_USER="burst-getorcreate-$RUN_ID"
FRESH_TOKEN="$(issue_token "$FRESH_USER")"
: > "$WORK_DIR/jobs1"
for i in $(seq 1 "$GET_OR_CREATE_BURST"); do
  echo "curl -sS -X POST '$BASE_URL/wallets' -H 'Authorization: Bearer $FRESH_TOKEN' -o '$WORK_DIR/w$i.json'" >> "$WORK_DIR/jobs1"
done
run_parallel "$WORK_DIR/jobs1"
DISTINCT_WALLETS="$(jq -r .id "$WORK_DIR"/w*.json | sort -u | wc -l | tr -d ' ')"
check "exactly one wallet for a brand-new user" "$DISTINCT_WALLETS" "1"

# ---------------------------------------------------------------- setup
say "Seeding wallets"
declare -a TOKENS WALLETS
for i in 0 1 2 3; do
  user="burst-player-$i-$RUN_ID"
  token="$(issue_token "$user")"
  wallet="$(api POST /wallets "$token" | jq -r .id)"
  api POST "/wallets/$wallet/topups" "$token" \
    "{\"amount_paise\":$SEED_PAISE,\"idempotency_key\":\"seed-$i-$RUN_ID\"}" > /dev/null
  TOKENS[i]="$token"; WALLETS[i]="$wallet"
  echo "  wallet $i: ${WALLETS[i]} funded with $SEED_PAISE paise"
done

balance_of() { api GET "/wallets/${WALLETS[$1]}" "${TOKENS[$1]}" | jq -r .balance_paise; }
total_balance() { local sum=0; for i in 0 1 2 3; do sum=$((sum + $(balance_of "$i"))); done; echo "$sum"; }

TOTAL_BEFORE="$(total_balance)"

# ---------------------------------------------------------------- probe 2
say "Probe 2 — idempotent retry storm ($IDEMPOTENCY_STORM concurrent sends of one transfer)"
STORM_KEY="storm-$RUN_ID"
STORM_BODY="{\"from\":\"${WALLETS[0]}\",\"to\":\"${WALLETS[1]}\",\"amount_paise\":5000,\"idempotency_key\":\"$STORM_KEY\"}"
SENDER_BEFORE="$(balance_of 0)"
RECEIVER_BEFORE="$(balance_of 1)"
: > "$WORK_DIR/jobs2"
for i in $(seq 1 "$IDEMPOTENCY_STORM"); do
  echo "curl -sS -X POST '$BASE_URL/transfers' -H 'Authorization: Bearer ${TOKENS[0]}' -H 'Content-Type: application/json' -d '$STORM_BODY' -o '$WORK_DIR/t$i.json' -w '%{http_code}\n' >> '$WORK_DIR/codes2'" >> "$WORK_DIR/jobs2"
done
: > "$WORK_DIR/codes2"
run_parallel "$WORK_DIR/jobs2"
DISTINCT_TRANSFERS="$(jq -r .id "$WORK_DIR"/t*.json | sort -u | wc -l | tr -d ' ')"
DISTINCT_BODIES="$(jq -Sc . "$WORK_DIR"/t*.json | sort -u | wc -l | tr -d ' ')"
DISTINCT_CODES="$(sort -u "$WORK_DIR/codes2" | tr '\n' ' ')"
check "one transfer id across all $IDEMPOTENCY_STORM responses" "$DISTINCT_TRANSFERS" "1"
check "every response body identical" "$DISTINCT_BODIES" "1"
check "every response status identical" "$(echo "$DISTINCT_CODES" | wc -w | tr -d ' ')" "1"
check "sender debited exactly once" "$(( SENDER_BEFORE - $(balance_of 0) ))" "5000"
check "receiver credited exactly once" "$(( $(balance_of 1) - RECEIVER_BEFORE ))" "5000"

say "Probe 2b — same idempotency key, different body"
CONFLICT_CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer ${TOKENS[0]}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${WALLETS[0]}\",\"to\":\"${WALLETS[1]}\",\"amount_paise\":9999,\"idempotency_key\":\"$STORM_KEY\"}")"
check "reused key with a different body is rejected" "$CONFLICT_CODE" "409"

# ---------------------------------------------------------------- probe 3
say "Probe 3 — conservation under contention ($CONTENTION_TRANSFERS transfers, both directions, some overdrawing)"
: > "$WORK_DIR/jobs3"
for i in $(seq 1 "$CONTENTION_TRANSFERS"); do
  from=$(( i % 4 )); to=$(( (i + 1 + i % 3) % 4 ))
  [ "$from" = "$to" ] && continue
  amount=$TRANSFER_PAISE
  [ $(( i % 17 )) -eq 0 ] && amount=$(( SEED_PAISE * 50 ))   # deliberately unaffordable
  body="{\"from\":\"${WALLETS[$from]}\",\"to\":\"${WALLETS[$to]}\",\"amount_paise\":$amount,\"idempotency_key\":\"load-$i-$RUN_ID\"}"
  echo "curl -sS -o /dev/null -X POST '$BASE_URL/transfers' -H 'Authorization: Bearer ${TOKENS[$from]}' -H 'Content-Type: application/json' -d '$body' -w '%{http_code}\n' >> '$WORK_DIR/codes3'" >> "$WORK_DIR/jobs3"
done
: > "$WORK_DIR/codes3"
run_parallel "$WORK_DIR/jobs3"
SERVER_ERRORS="$(grep -c '^5' "$WORK_DIR/codes3" || true)"
DECLINED="$(grep -c '^422' "$WORK_DIR/codes3" || true)"
echo "  $(wc -l < "$WORK_DIR/codes3" | tr -d ' ') sent, $DECLINED declined for insufficient funds, $SERVER_ERRORS server errors"
check "no server errors under contention" "$SERVER_ERRORS" "0"
check "total balance unchanged" "$(total_balance)" "$TOTAL_BEFORE"

NEGATIVE=0
for i in 0 1 2 3; do [ "$(balance_of "$i")" -lt 0 ] && NEGATIVE=$((NEGATIVE + 1)); done
check "no wallet went negative" "$NEGATIVE" "0"

# ---------------------------------------------------------------- ledger
if [ -n "${ADMIN_TOKEN:-}" ]; then
  say "Ledger-wide invariants (/admin/invariants)"
  INVARIANTS="$(curl -sS "$BASE_URL/admin/invariants" -H "X-Admin-Token: $ADMIN_TOKEN")"
  echo "$INVARIANTS" | jq .
  check "double-entry ledger sums to zero" "$(echo "$INVARIANTS" | jq -r .conserved)" "true"
fi

say "Result"
if [ "$FAILURES" -eq 0 ]; then printf '  \033[32mall invariants held\033[0m\n'; else printf '  \033[31m%s check(s) failed\033[0m\n' "$FAILURES"; fi
exit "$FAILURES"

#!/usr/bin/env bash
# End-to-end demo with real processes: 3 gateways (Raft cluster) + 3 backends.
#   1. register the backends through a FOLLOWER (the write is forwarded to the leader and replicated)
#   2. send traffic, then make one backend slow and watch the adaptive router avoid it
#   3. kill the Raft leader and show routing continues from replicated config
#   4. add a backend through a surviving node (the control plane is writable again)
# Needs: java 21+, curl. Builds out/aegismesh.jar first if it is missing.
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=out/aegismesh.jar
[ -f "$JAR" ] || { echo "== building"; make build >/dev/null; }

DATA=$(mktemp -d)
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; rm -rf "$DATA"; }
trap cleanup EXIT

PEERS="gw1=127.0.0.1:19101,gw2=127.0.0.1:19102,gw3=127.0.0.1:19103"
declare -A GWPID
for i in 1 2 3; do
  java -jar "$JAR" gateway -id gw$i -listen 127.0.0.1:1810$i -peers "$PEERS" -data "$DATA" \
       -health-interval 200ms > "$DATA/gw$i.log" 2>&1 &
  GWPID[gw$i]=$!; PIDS+=($!)
done
for i in 1 2 3; do
  java -jar "$JAR" backend -id w$i -listen 127.0.0.1:1700$i > "$DATA/w$i.log" 2>&1 &
  PIDS+=($!)
done

wait_for() { # wait_for <description> <command...>: poll up to 20 s
  local what=$1; shift
  for _ in $(seq 1 200); do "$@" >/dev/null 2>&1 && return 0; sleep 0.1; done
  echo "timed out waiting for $what" >&2; exit 1
}
for i in 1 2 3; do wait_for "gateway gw$i" curl -fsS -m 1 "127.0.0.1:1810$i/healthz"; done
for i in 1 2 3; do wait_for "backend w$i" curl -fsS -m 1 "127.0.0.1:1700$i/healthz"; done

leader() { # prints the id of the node that reports itself leader
  for i in 1 2 3; do
    if curl -fsS -m 1 "127.0.0.1:1910$i/admin/status" 2>/dev/null | grep -q '"role":"leader"'; then echo "gw$i"; return 0; fi
  done
  return 1
}
wait_leader() { # wait_leader [not-this-id]
  for _ in $(seq 1 200); do
    local l; l=$(leader || true)
    if [ -n "$l" ] && [ "$l" != "${1:-}" ]; then echo "$l"; return 0; fi
    sleep 0.1
  done
  echo "no leader elected within 20 s" >&2; return 1
}
wait_ready() { wait_for "gateway on port $1 to be ready" curl -fsS -m 1 "127.0.0.1:$1/readyz"; }
count() { # count <data-port> <n>: how many requests each backend served
  for _ in $(seq 1 "$2"); do
    curl -s -D - -o /dev/null "127.0.0.1:$1/api/work" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-aegis-backend"{print $2}'
  done | sort | uniq -c | awk '{printf "  %4d %s", $1, $2} END {print ""}'
}

echo "== leader: $(wait_leader)"
echo "== register 3 backends + adaptive policy via follower gw2 (forwarded to the leader)"
for i in 1 2 3; do
  until curl -fsS -m 3 -XPOST 127.0.0.1:19102/admin/backends \
      -d "{\"id\":\"w$i\",\"service\":\"api\",\"addr\":\"127.0.0.1:1700$i\"}" >/dev/null 2>&1; do sleep 0.2; done
done
curl -fsS -XPOST 127.0.0.1:19102/admin/policy -d '{"service":"api","policy":"adaptive"}' >/dev/null
wait_ready 18103
echo "== 60 requests via gw3, all backends healthy:"; count 18103 60

echo "== make w1 slow (+200 ms) and send 120 more:"
curl -fsS -XPOST 127.0.0.1:17001/_fault -d '{"extra_ms":200}' >/dev/null
count 18103 120

L=$(wait_leader)
echo "== killing leader $L"
{ kill -9 "${GWPID[$L]}"; wait "${GWPID[$L]}"; } 2>/dev/null || true
echo "== new leader: $(wait_leader "$L")"
SURV=$(for i in 1 2 3; do [ "gw$i" != "$L" ] && echo "1810$i" && break; done)
SURVCTL=$(for i in 1 2 3; do [ "gw$i" != "$L" ] && echo "1910$i" && break; done)
wait_ready "$SURV"
echo "== routing continues from replicated config via port $SURV:"; count "$SURV" 60

echo "== add a backend through a surviving node (control plane writable again)"
java -jar "$JAR" backend -id w4 -listen 127.0.0.1:17004 > "$DATA/w4.log" 2>&1 & PIDS+=($!)
wait_for "backend w4" curl -fsS -m 1 127.0.0.1:17004/healthz
until curl -fsS -m 3 -XPOST "127.0.0.1:$SURVCTL/admin/backends" \
    -d '{"id":"w4","service":"api","addr":"127.0.0.1:17004"}' >/dev/null 2>&1; do sleep 0.2; done
echo "registry: $(curl -fsS "127.0.0.1:$SURVCTL/admin/status" | grep -o '"w[0-9]":' | tr -d '":' | sort | tr '\n' ' ')"

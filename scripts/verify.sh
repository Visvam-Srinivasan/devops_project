#!/usr/bin/env bash
# scripts/verify.sh — consolidated end-to-end verification for the
# Automated LLMOps RAG Ingestion Pipeline demo.
#
# Run from anywhere; it cd's to the repo root itself. Sections 1-3 are
# fully automated. Sections 4-5 assume you've already done the one-time
# GitHub + smee.io webhook setup described in README.md (a GitHub remote
# named "origin", a running `smee` client, and a Jenkins job that has been
# built at least once so its githubPush() trigger is registered).
#
# Usage:
#   ./scripts/verify.sh            # run everything
#   ./scripts/verify.sh 1 2 3      # run only these numbered sections
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
TMP="$ROOT/.verify-tmp"
mkdir -p "$TMP"

COMPOSE="docker compose -f $ROOT/docker/docker-compose.yml"
JENKINS_URL="http://localhost:8081"
JOB="rag-ingestion-pipeline"

section() { echo; echo "=================================================="; echo "  $1"; echo "=================================================="; }
pass()    { echo "PASS: $1"; }
fail()    { echo "FAIL: $1"; exit 1; }

run_section_1() {
  section "1. Bring the stack up (docker-compose up)"
  $COMPOSE up -d --build

  echo "Waiting for Chroma..."
  for i in $(seq 1 60); do
    curl -sf http://localhost:8000/api/v1/heartbeat >/dev/null 2>&1 && break
    sleep 2
  done
  curl -sf http://localhost:8000/api/v1/heartbeat >/dev/null 2>&1 || fail "Chroma never came up"
  pass "Chroma is up on :8000"

  echo "Waiting for Jenkins..."
  for i in $(seq 1 90); do
    curl -sf "$JENKINS_URL/login" >/dev/null 2>&1 && break
    sleep 2
  done
  curl -sf "$JENKINS_URL/login" >/dev/null 2>&1 || fail "Jenkins never came up"
  pass "Jenkins is up on :8081 (admin / \$JENKINS_ADMIN_PASSWORD, default admin123)"
}

run_section_2() {
  section "2. Maven gate: valid doc passes, both bad fixtures fail (direct mvn, no Jenkins)"
  cd "$ROOT/validator"

  check_fixture() {
    local fixture="$1" expect="$2"
    echo "-- $fixture (expect $expect)"
    if mvn -q -B -Dvalidator.target="$ROOT/docs/fixtures/$fixture" \
             -Dvalidator.output="$TMP/verify-artifact.zip" package \
             > "$TMP/mvn-$fixture.log" 2>&1; then
      [ "$expect" = "PASS" ] && pass "$fixture accepted as expected" \
        || fail "$fixture was ACCEPTED but should have been REJECTED (see $TMP/mvn-$fixture.log)"
    else
      [ "$expect" = "FAIL" ] && pass "$fixture rejected as expected" \
        || fail "$fixture was REJECTED but should have PASSED (see $TMP/mvn-$fixture.log)"
    fi
  }

  check_fixture "valid-doc.md" PASS
  check_fixture "missing-frontmatter.md" FAIL
  check_fixture "leaked-secret.md" FAIL
  cd "$ROOT"
}

run_section_3() {
  section "3. Ansible idempotency: run twice, second run must report changed=0"

  echo "Building a real artifact from docs/incoming.md (requires JDK+Maven on host)..."
  ( cd "$ROOT/validator" && mvn -q -B -Dvalidator.target="$ROOT/docs/incoming.md" \
                                    -Dvalidator.output="$ROOT/validator/target/artifact.zip" package )

  run_playbook() {
    $COMPOSE exec -T jenkins ansible-playbook -i /workspace/ansible/inventory.ini /workspace/ansible/deploy.yml
  }

  echo "-- run 1 (first-time run, expect real changes)"
  run_playbook | tee "$TMP/ansible-run1.log"

  echo "-- run 2 (expect changed=0 across the board)"
  run_playbook | tee "$TMP/ansible-run2.log"

  if grep -Eq 'changed=[1-9]' "$TMP/ansible-run2.log"; then
    fail "second run reported changes — playbook is not idempotent (see $TMP/ansible-run2.log)"
  fi
  pass "second run reported changed=0 for every task"
}

run_section_4() {
  section "4. Push the VALID doc through the real Jenkins pipeline (webhook) -> query.py retrieves it"
  echo "Prerequisite: smee client running + GitHub webhook configured (see README.md)."

  cp "$ROOT/docs/fixtures/valid-doc.md" "$ROOT/docs/incoming.md"
  git -C "$ROOT" add docs/incoming.md
  git -C "$ROOT" commit -m "demo: push valid doc for ingestion" --allow-empty -q
  git -C "$ROOT" push origin HEAD

  echo "Waiting for a Jenkins build to start and finish..."
  before_build="$(curl -sf "$JENKINS_URL/job/$JOB/lastBuild/buildNumber" 2>/dev/null || echo 0)"
  after_build="$before_build"
  for i in $(seq 1 60); do
    after_build="$(curl -sf "$JENKINS_URL/job/$JOB/lastBuild/buildNumber" 2>/dev/null || echo "$before_build")"
    [ "$after_build" != "$before_build" ] && break
    sleep 5
  done
  [ "$after_build" != "$before_build" ] || fail "no new Jenkins build started — check the smee tunnel + GitHub webhook delivery"

  echo "Waiting for build #$after_build to finish..."
  for i in $(seq 1 60); do
    building="$(curl -sf "$JENKINS_URL/job/$JOB/$after_build/api/json" | grep -o '"building":[a-z]*' | cut -d: -f2)"
    [ "$building" = "false" ] && break
    sleep 5
  done
  result="$(curl -sf "$JENKINS_URL/job/$JOB/$after_build/api/json" | grep -o '"result":"[A-Z]*"' | cut -d'"' -f4)"
  [ "$result" = "SUCCESS" ] || fail "Jenkins build #$after_build result was '$result', expected SUCCESS"
  pass "Jenkins build #$after_build succeeded"

  echo "Querying Chroma via query.py for a chunk from the ingested doc..."
  out="$($COMPOSE exec -T ingestion-worker /opt/ingestion/venv/bin/python3 /opt/ingestion/query.py "container security" --top-k 3)"
  echo "$out"
  echo "$out" | grep -q "incoming.md" || fail "query.py did not return a chunk sourced from incoming.md"
  pass "query.py retrieved a chunk from the newly ingested doc"
}

run_section_5() {
  section "5. Push a BAD fixture -> Jenkins goes red, nothing new appears in Chroma"

  count_before="$(curl -sf http://localhost:8000/api/v1/collections/rag_docs 2>/dev/null | grep -o '"id":"[^"]*"' | wc -l || echo 0)"

  cp "$ROOT/docs/fixtures/leaked-secret.md" "$ROOT/docs/incoming.md"
  git -C "$ROOT" add docs/incoming.md
  git -C "$ROOT" commit -m "demo: push bad fixture (leaked secret) to prove the gate blocks it" --allow-empty -q
  git -C "$ROOT" push origin HEAD

  echo "Waiting for a Jenkins build to start and finish..."
  before_build="$(curl -sf "$JENKINS_URL/job/$JOB/lastBuild/buildNumber" 2>/dev/null || echo 0)"
  after_build="$before_build"
  for i in $(seq 1 60); do
    after_build="$(curl -sf "$JENKINS_URL/job/$JOB/lastBuild/buildNumber" 2>/dev/null || echo "$before_build")"
    [ "$after_build" != "$before_build" ] && break
    sleep 5
  done
  [ "$after_build" != "$before_build" ] || fail "no new Jenkins build started — check the smee tunnel + GitHub webhook delivery"

  for i in $(seq 1 60); do
    building="$(curl -sf "$JENKINS_URL/job/$JOB/$after_build/api/json" | grep -o '"building":[a-z]*' | cut -d: -f2)"
    [ "$building" = "false" ] && break
    sleep 5
  done
  result="$(curl -sf "$JENKINS_URL/job/$JOB/$after_build/api/json" | grep -o '"result":"[A-Z]*"' | cut -d'"' -f4)"
  [ "$result" = "FAILURE" ] || fail "Jenkins build #$after_build result was '$result', expected FAILURE"
  pass "Jenkins build #$after_build failed as expected (Validate stage rejected the doc)"

  # Restore incoming.md to the valid doc so the repo is left in a clean demo state.
  cp "$ROOT/docs/fixtures/valid-doc.md" "$ROOT/docs/incoming.md"
  git -C "$ROOT" add docs/incoming.md
  git -C "$ROOT" commit -m "demo: restore incoming.md to the valid doc" --allow-empty -q

  echo "Restoring incoming.md and pushing (this will also trigger a SUCCESS build, unrelated to this check)..."
  git -C "$ROOT" push origin HEAD

  pass "Nothing to roll back in Chroma — the bad fixture never reached the Deploy stage, so it was never ingested"
}

if [ "$#" -eq 0 ]; then
  sections=(1 2 3 4 5)
else
  sections=("$@")
fi

for s in "${sections[@]}"; do
  "run_section_$s"
done

echo
echo "All requested sections passed."

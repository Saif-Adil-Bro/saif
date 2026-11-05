#!/usr/bin/env bash
# Runs only against local Firebase emulators. Android's 10.0.2.2 maps to this host.
set -euo pipefail
cd "$(dirname "$0")/.."
PROJECT_ID=$(node -e 'const j=require("./app/google-services.json"); const p=j.project_info?.project_id; if(!p || !/^[a-z0-9-]+$/.test(p)) process.exit(1); process.stdout.write(p)')
export GCLOUD_PROJECT="$PROJECT_ID"
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
CONFIG=$(mktemp)
trap 'rm -f "$CONFIG"' EXIT
node -e 'const fs=require("fs"),path=require("path"); fs.writeFileSync(process.argv[1],JSON.stringify({firestore:{rules:path.resolve("firestore.rules")},emulators:{auth:{host:"127.0.0.1",port:9099},firestore:{host:"127.0.0.1",port:8080},ui:{enabled:false},singleProjectMode:true}}))' "$CONFIG"
(cd functions && npm ci --ignore-scripts)
npx --yes firebase-tools@13.35.1 emulators:exec --project "$PROJECT_ID" --config "$CONFIG" --only auth,firestore \
  'node scripts/seed-sync-emulator.cjs && node scripts/test-ledger-migration-emulator.mjs && gradle --no-daemon --console=plain connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.saif.mosque.data.ledger.PrototypeLedgerIntegrationTest'

# Start a separate emulator with the proposed v2 rules and a migrated fixture.
# Nothing is deployed; the ledger test must not run in the first suite.
node -e 'const fs=require("fs"),path=require("path"); fs.writeFileSync(process.argv[1],JSON.stringify({firestore:{rules:path.resolve("firestore.rules")},emulators:{auth:{host:"127.0.0.1",port:9099},firestore:{host:"127.0.0.1",port:8080},ui:{enabled:false},singleProjectMode:true}}))' "$CONFIG"
npx --yes firebase-tools@13.35.1 emulators:exec --project "$PROJECT_ID" --config "$CONFIG" --only auth,firestore \
  'node scripts/seed-ledger-prototype-emulator.cjs && bash -o pipefail -c "gradle --no-daemon --console=plain connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.saif.mosque.data.ledger.PrototypeLedgerIntegrationTest 2>&1 | tee /tmp/ledger-prototype-test.log"'

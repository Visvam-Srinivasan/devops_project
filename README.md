# Automated LLMOps Pipeline for Secure RAG Knowledge Ingestion

Course project prototype (CS23016, Anna University). A markdown doc gets
pushed to GitHub, a headless Jenkins validates it (frontmatter + secret
scan), packages it, deploys it via Ansible to a stand-in "VM", and ingests
it into a local ChromaDB vector store with locally-computed embeddings —
no cloud accounts, no external APIs, everything on `docker compose`.

This is a demo/prototype. It's built to demo reliably, not to be
production-grade — see "Known simplifications" at the bottom.

## Architecture

```
 developer            GitHub repo            your laptop (docker compose)
 ┌────────┐  git push  ┌──────────┐  webhook  ┌─────────────────────────────────────────────┐
 │ docs/  │───────────►│  origin  │──────────►│  smee client (localhost:8081/github-webhook/)│
 │incoming│            └──────────┘  (push)   └───────────────────┬───────────────────────────┘
 │  .md   │                                                       │
 └────────┘                                                       ▼
                                                     ┌───────────────────────────┐
                                                     │  jenkins (container)      │
                                                     │  Checkout -> Validate ->  │
                                                     │  Archive -> Deploy        │
                                                     │  (Maven + Ansible inside) │
                                                     └─────────────┬─────────────┘
                                                                   │ ansible-playbook
                                                                   │ over SSH (docker network)
                                                                   ▼
                                                     ┌───────────────────────────┐
                                                     │ ingestion-worker (container)│
                                                     │ Ubuntu + sshd, stand-in VM │
                                                     │ runs ingest.py / query.py  │
                                                     │ in a venv (sentence-      │
                                                     │ transformers, all-MiniLM) │
                                                     └─────────────┬─────────────┘
                                                                   │ HTTP (chromadb client)
                                                                   ▼
                                                     ┌───────────────────────────┐
                                                     │ chroma (container)        │
                                                     │ official image, port 8000 │
                                                     └───────────────────────────┘
```

The Maven "Validate" gate is the security control: it checks YAML
frontmatter (`title`, `source`, `version` required), enforces a max file
size, and regex-scans the doc body for secret/PII-shaped strings (AWS
access keys, generic API-key/token/password assignments, emails,
card-number-shaped digit runs). If it rejects the doc, the pipeline stops
there — nothing gets archived, deployed, or ingested.

## Repo layout

```
docs/fixtures/     3 fixture markdown docs used by the JUnit tests:
                   valid-doc.md, missing-frontmatter.md, leaked-secret.md
docs/incoming.md   The file Jenkins actually validates on every push.
                   The two demo pushes below just overwrite this file with
                   one of the fixtures above and push.
validator/         Maven project: DocValidator.java + JUnit tests.
                   `mvn package` runs the gate and, on success, produces
                   validator/target/artifact.zip (doc + manifest.json).
ansible/           inventory.ini + deploy.yml + the demo SSH keypair
                   (ansible/ssh/) used to reach the ingestion-worker "VM".
ingestion/         ingest.py (unzip, chunk, embed, upsert to Chroma) and
                   query.py (CLI: ask a question, get back matching chunks).
docker/            docker-compose.yml + the jenkins/ and ingestion-worker/
                   Dockerfiles, plugins.txt, and the JCasC config.
Jenkinsfile        Checkout -> Validate -> Archive artifact -> Deploy.
scripts/verify.sh  The consolidated verification script (see below).
```

## One-time setup (needs internet — do this before going offline for the demo)

These are the only steps that touch the network. Do them once, ahead of
time, and the actual demo (pushing docs, running the pipeline, querying
Chroma) runs fully offline.

1. **Docker image build** (`docker compose build` / `docker compose up
   --build`) — pulls the `ubuntu:22.04`, `jenkins/jenkins:lts-jdk17`, and
   `chromadb/chroma:0.5.5` base images, apt-installs Maven/Ansible/git/ssh
   into the Jenkins image, and runs `jenkins-plugin-cli` to fetch
   `docker/jenkins/plugins.txt` from the Jenkins update center.
2. **smee client** — `npx smee-client ...` (or `npm install -g
   smee-client`) downloads the smee CLI from npm the first time you run it.
3. **Embedding model + Python deps** — the *first* Ansible run installs
   `sentence-transformers`/`chromadb` (and their dependencies, incl.
   PyTorch — this is the biggest download) into a venv on the
   ingestion-worker, and the *first* `ingest.py`/`query.py` run downloads
   the `all-MiniLM-L6-v2` model from Hugging Face (~90 MB). Both are
   cached in named Docker volumes (`hf-cache`, and the venv lives inside
   the `ingestion-worker` container's filesystem) so later runs — and
   later `docker compose up` cycles, as long as you don't `docker compose
   down -v` — are fully offline.
4. **GitHub webhook delivery itself** is obviously a call to GitHub's
   servers (the whole point of the Git/GitHub step), but that's inherent
   to the assignment's chosen stack, not an extra dependency to remove.

Everything else — Maven builds, the secret scan, Ansible deploys, chunking,
embedding, querying — runs entirely against localhost containers.

## Setup steps

```bash
# 1. Push this repo to a GitHub repo you control (needed for the webhook demo).
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main

# 2. Point Jenkins' auto-created job at your repo.
cp docker/.env.example docker/.env
# edit docker/.env -> set GIT_REPO_URL to the URL above

# 3. Build + start the stack (needs internet the first time — see above).
docker compose -f docker/docker-compose.yml --env-file docker/.env up -d --build

# 4. Open Jenkins at http://localhost:8081 (admin / admin123 by default)
#    and click "Build Now" on rag-ingestion-pipeline once — this registers
#    the githubPush() trigger defined in the Jenkinsfile. It should go
#    green (docs/incoming.md starts out as a copy of the valid fixture).

# 5. Start the smee tunnel (forwards GitHub's webhook POSTs to your local
#    Jenkins). Pick a channel at https://smee.io/new, then:
npx smee-client --url https://smee.io/<your-channel> \
                 --path /github-webhook/ --port 8081

# 6. On GitHub: repo Settings -> Webhooks -> Add webhook
#    Payload URL: https://smee.io/<your-channel>
#    Content type: application/json
#    Events: "Just the push event"
```

Chroma is reachable at `http://localhost:8000`, Jenkins at
`http://localhost:8081` (this stack's Jenkins is published on 8081, not
8080 — 8080 was already taken by another Jenkins instance on this
machine), and you can `ssh -p 2222 deploy@localhost -i
ansible/ssh/id_ed25519` into the ingestion-worker "VM" for debugging.

## The two demo pushes

**Valid doc** — proves the whole pipeline end to end:

```bash
cp docs/fixtures/valid-doc.md docs/incoming.md
git add docs/incoming.md && git commit -m "push valid doc"
git push origin HEAD
```
Expected: Jenkins build goes **green** (Checkout, Validate, Archive
artifact, Deploy all succeed). Then:
```bash
docker compose -f docker/docker-compose.yml exec ingestion-worker \
  /opt/ingestion/venv/bin/python3 /opt/ingestion/query.py "container security"
```
returns chunks whose `source` is `incoming.md`.

**Bad fixture** — proves the gate actually blocks a leak:

```bash
cp docs/fixtures/leaked-secret.md docs/incoming.md   # or missing-frontmatter.md
git add docs/incoming.md && git commit -m "push bad fixture"
git push origin HEAD
```
Expected: Jenkins build goes **red** at the **Validate** stage (Maven's
`mvn package` fails because `DocValidator` rejected the doc — the log
shows exactly which rule matched). Archive artifact and Deploy never run,
so nothing new appears in Chroma.

## Consolidated verification

`scripts/verify.sh` runs (or you can run manually, section by section):

1. Brings the stack up and waits for Chroma + Jenkins to be healthy.
2. Runs `mvn package` directly against each of the 3 fixtures and checks
   the valid one passes while both bad ones fail (no Jenkins involved).
3. Runs `ansible-playbook` twice back-to-back (via the same `jenkins`
   container the real Deploy stage uses, against a read-only `/workspace`
   mount of the repo) and asserts the second run reports `changed=0`.
4. Pushes the valid doc through the real Jenkins pipeline over the smee
   webhook, waits for a green build, then runs `query.py` and asserts it
   retrieves a chunk sourced from `incoming.md`.
5. Pushes `leaked-secret.md`, asserts the build goes red, and confirms
   nothing new was ingested (the bad doc never reaches the Deploy stage in
   the first place, so there's nothing to roll back).

```bash
./scripts/verify.sh          # all 5 sections
./scripts/verify.sh 1 2 3    # just the offline sections
```

Sections 4-5 assume you've already done the GitHub remote + smee + webhook
setup above; they `git push` for you as part of the check.

## Known simplifications vs. production

- **Secret/PII scanning is shape-based regex**, not a real DLP engine —
  fine for a demo, would miss anything not matching AWS-key/API-key/email/
  card-number shapes and would false-positive on some legitimate text.
- **One demo SSH keypair, committed to the repo** (`ansible/ssh/`) instead
  of per-environment keys minted through a secrets manager or Vault.
- **Jenkins admin password is a static default** (`admin123`), not backed
  by SSO/LDAP, and Jenkins is never exposed outside `localhost`.
- **A single `docs/incoming.md` "inbox" file** stands in for a real
  intake queue / multi-doc batch ingestion pipeline.
- **No retries, backoff, or circuit breaking** anywhere (webhook delivery,
  Ansible SSH, Chroma HTTP calls) — a transient failure just fails the
  build/task.
- **Chunking is a naive fixed-size sliding window**, not
  sentence/paragraph-aware or model-context-aware.
- **`ingest.py`/`query.py` print a harmless `Failed to send telemetry
  event` line to stderr** — a known upstream version mismatch between
  `chromadb==0.5.5`'s bundled telemetry client and `posthog`. Cosmetic
  only; doesn't affect exit codes or results.
- **No authentication on the Chroma HTTP API** and no TLS anywhere in the
  stack — every hop is plaintext on a local docker network.
- **Single-node everything** — one Jenkins controller with no agents, one
  Chroma instance with no replication, one ingestion worker.

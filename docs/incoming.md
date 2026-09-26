---
title: "Container Security Basics"
source: "internal-wiki"
version: "1.0"
---

# Container Security Basics

Containers should run as non-root users whenever possible. Building images
from minimal base layers reduces the attack surface and keeps scan times
short. Always pin base image tags instead of relying on "latest" so that
builds stay reproducible across environments.

Read-only root filesystems and dropped Linux capabilities are cheap ways to
limit what a compromised process can do inside a container. Combine this
with a network policy that denies traffic by default, then explicitly
allow only the ports a service actually needs.

Regularly rebuild images so that they pick up patched base layers, and keep
an eye on your dependency manifests for known vulnerable packages. A short
feedback loop between scanning and rebuilding is more valuable than a
perfect one-time audit.

# ingestion-worker: stands in for a real target VM that Ansible deploys to.
# Ubuntu + sshd, reachable by Ansible over key-only SSH as the "deploy" user.
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        openssh-server sudo python3 python3-venv python3-pip unzip ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /var/run/sshd

RUN useradd -m -s /bin/bash deploy && \
    echo "deploy ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers && \
    mkdir -p /home/deploy/.ssh && chmod 700 /home/deploy/.ssh && \
    # Pre-create the HF cache dir owned by deploy: docker-compose mounts a
    # named volume here, and a named volume takes on the ownership of
    # whatever already exists at its mount point on first use — without
    # this, Docker creates it as root and sentence-transformers (running
    # as "deploy") can't write the downloaded model into it.
    mkdir -p /home/deploy/.cache/huggingface && \
    chown -R deploy:deploy /home/deploy/.cache

# Public half of the demo keypair generated in ansible/ssh/ — build context
# is the repo root (see docker-compose.yml), so this path resolves there.
COPY ansible/ssh/id_ed25519.pub /home/deploy/.ssh/authorized_keys
RUN chown -R deploy:deploy /home/deploy/.ssh && chmod 600 /home/deploy/.ssh/authorized_keys

# Key-only auth — this "VM" never accepts a password.
RUN sed -i 's/#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config

EXPOSE 22
CMD ["/usr/sbin/sshd", "-D"]

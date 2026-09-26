# jenkins: headless (no setup wizard) CI controller, provisioned entirely
# via plugins.txt + JCasC. Also doubles as the Ansible control node — it
# has Maven, Ansible, and an SSH client baked in, and the demo SSH key used
# to reach the ingestion-worker "VM".
FROM jenkins/jenkins:lts-jdk17

USER root

# Maven -> Validate stage. Ansible -> Deploy stage. git -> Checkout stage
# (also needed by jenkins-plugin-cli itself). openssh-client -> Ansible's
# SSH connection to ingestion-worker.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        maven ansible git openssh-client python3 && \
    rm -rf /var/lib/apt/lists/*

# Skip the interactive setup wizard entirely — provisioned headlessly below.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc_configs/jenkins.yaml

COPY docker/jenkins/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY docker/jenkins/jenkins.yaml /var/jenkins_home/casc_configs/jenkins.yaml

# SSH key Ansible uses to reach the ingestion-worker "VM" (see ansible/inventory.ini).
RUN mkdir -p /var/jenkins_home/.ssh
COPY ansible/ssh/id_ed25519 /var/jenkins_home/.ssh/id_ed25519
RUN chown -R jenkins:jenkins /var/jenkins_home/.ssh /var/jenkins_home/casc_configs && \
    chmod 700 /var/jenkins_home/.ssh && \
    chmod 600 /var/jenkins_home/.ssh/id_ed25519

USER jenkins

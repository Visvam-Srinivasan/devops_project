// Jenkinsfile — Automated LLMOps Pipeline for Secure RAG Knowledge Ingestion
//
// Stages: Checkout -> Validate -> Archive artifact -> Deploy
//
// Declarative pipelines stop at the first failed stage by default, so if
// Validate fails (bad frontmatter, a leaked secret, an oversize file),
// Archive and Deploy simply never run — nothing bad is ever packaged,
// shipped, or ingested.
pipeline {
    agent any

    options {
        // Keep old build workspaces from piling up on this small demo box.
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
    }

    triggers {
        // Registers the well-known /github-webhook/ endpoint (served by the
        // GitHub plugin). smee.io forwards GitHub's push webhook to this
        // Jenkins on localhost, which fires this trigger.
        githubPush()
    }

    environment {
        // The ingestion-worker "VM" has host key checking disabled anyway
        // (see ansible.cfg), but belt-and-braces here too.
        ANSIBLE_HOST_KEY_CHECKING = 'false'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Validate') {
            steps {
                // "mvn package" runs the JUnit fixture tests, then (via the
                // exec-maven-plugin binding in validator/pom.xml) runs
                // DocValidator against docs/incoming.md — the file the
                // demo pushes overwrite. A rejection makes DocValidator
                // exit non-zero, which fails this whole stage.
                dir('validator') {
                    sh 'mvn -B test package'
                }
            }
        }

        stage('Archive artifact') {
            steps {
                archiveArtifacts artifacts: 'validator/target/artifact.zip', fingerprint: true
            }
        }

        stage('Deploy') {
            steps {
                // Idempotent: installs deps, ships the artifact + ingestion
                // scripts, and ingests the doc into Chroma (skipped if its
                // sha256 was already ingested). See ansible/deploy.yml.
                dir('ansible') {
                    sh 'ansible-playbook -i inventory.ini deploy.yml'
                }
            }
        }
    }

    post {
        success {
            echo 'Pipeline succeeded: document validated, packaged, deployed, and ingested into Chroma.'
        }
        failure {
            echo 'Pipeline failed — check the Validate stage log above for the rejection reason.'
        }
    }
}

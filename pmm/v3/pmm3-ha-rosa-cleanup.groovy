/**
 * PMM HA ROSA Cleanup - Manages cleanup of PMM HA ROSA HCP clusters.
 *
 * Self-contained single-file pipeline (no external library dependencies).
 *
 * Supports actions:
 * - LIST_ONLY: Show all PMM HA ROSA clusters without deleting
 * - DELETE_CLUSTER: Delete a specific cluster by name
 * - DELETE_ALL: Delete all PMM HA ROSA clusters
 * - DELETE_OLD: (cron only) Delete clusters older than 24 hours
 *
 * Designed to run on a schedule (cron) for cost management.
 */

pipeline {
    agent { label 'agent-amd64-ol9' }

    triggers {
        cron('H 0,12 * * *') // Runs twice daily at 00:00 & 12:00
    }

    parameters {
        choice(
            name: 'ACTION',
            choices: ['LIST_ONLY', 'DELETE_CLUSTER', 'DELETE_ALL'],
            description: '''
                LIST_ONLY - List all PMM HA ROSA clusters<br/>
                DELETE_CLUSTER - Delete a specific cluster (requires CLUSTER_NAME)<br/>
                DELETE_ALL - Delete all PMM HA ROSA clusters<br/><br/>
                Note: Daily cron automatically deletes clusters older than 24 hours.
            '''
        )
        string(
            name: 'CLUSTER_NAME',
            defaultValue: '',
            description: 'Required only for DELETE_CLUSTER action'
        )
        booleanParam(
            name: 'DELETE_OPERATOR_ROLES',
            defaultValue: false,
            description: 'Delete operator roles when deleting cluster (WARNING: can break other clusters if shared)'
        )
        booleanParam(
            name: 'DELETE_VPC',
            defaultValue: true,
            description: 'Delete VPC CloudFormation stack when deleting cluster'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 120, unit: 'MINUTES')
        timestamps()
    }

    environment {
        REGION = 'us-east-2'
        CLUSTER_PREFIX = 'pmm-ha-rosa-'
        MAX_AGE_HOURS = '24'
        AWS_ACCOUNT_ID = '119175775298'
        OPERATOR_ROLE_PREFIX = 'pmm-rosa-ha'
    }

    stages {
        stage('Detect Run Type') {
            steps {
                script {
                    if (currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')) {
                        env.ACTION = 'DELETE_OLD'
                        echo "Triggered by cron - will delete clusters older than ${MAX_AGE_HOURS} hours."
                    } else {
                        env.ACTION = params.ACTION
                        echo "Manual run with ACTION=${params.ACTION}"
                    }

                    if (env.ACTION == 'DELETE_CLUSTER' && !params.CLUSTER_NAME) {
                        error("CLUSTER_NAME is required for DELETE_CLUSTER action.")
                    }
                    if (params.CLUSTER_NAME && !params.CLUSTER_NAME.startsWith(env.CLUSTER_PREFIX)) {
                        error("Cluster name must start with ${env.CLUSTER_PREFIX}")
                    }

                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.ACTION}"
                    currentBuild.description = "Action: ${env.ACTION}"
                }
            }
        }

        stage('Install ROSA CLI') {
            steps {
                sh '''
                    set -o errexit
                    set -o xtrace

                    # Check if rosa is already installed
                    if command -v rosa &>/dev/null; then
                        echo "ROSA CLI already installed: $(rosa version)"
                        exit 0
                    fi

                    # Download and install ROSA CLI
                    ROSA_URL="https://mirror.openshift.com/pub/openshift-v4/clients/rosa/latest/rosa-linux.tar.gz"
                    curl -sL "${ROSA_URL}" | tar -xz -C /tmp
                    sudo mv /tmp/rosa /usr/local/bin/rosa
                    sudo chmod +x /usr/local/bin/rosa

                    rosa version
                '''
            }
        }

        stage('Login to ROSA') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        set -o errexit
                        set -o xtrace

                        rosa login --token="${ROSA_TOKEN}"
                        rosa whoami
                    '''
                }
            }
        }

        stage('List Clusters') {
            when { expression { env.ACTION == 'LIST_ONLY' } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    sh '''
                        set -o errexit

                        echo "===== PMM HA ROSA CLUSTERS ====="

                        # Get all clusters as JSON
                        CLUSTERS_JSON=$(rosa list clusters -o json 2>/dev/null || echo "[]")

                        # Filter for PMM HA clusters and display
                        echo "${CLUSTERS_JSON}" | jq -r --arg prefix "${CLUSTER_PREFIX}" '
                            .[] | select(.name | startswith($prefix)) |
                            "• \\(.name) | State: \\(.state) | Region: \\(.region.id) | Created: \\(.creation_timestamp)"
                        ' || echo "No clusters found with prefix '${CLUSTER_PREFIX}'"

                        COUNT=$(echo "${CLUSTERS_JSON}" | jq --arg prefix "${CLUSTER_PREFIX}" '[.[] | select(.name | startswith($prefix))] | length')
                        echo "================================"
                        echo "Total PMM HA ROSA clusters: ${COUNT}"
                    '''
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { env.ACTION == 'DELETE_CLUSTER' } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh """
                        set -o errexit
                        set -o xtrace

                        CLUSTER_NAME="${params.CLUSTER_NAME}"
                        DELETE_OPERATOR_ROLES="${params.DELETE_OPERATOR_ROLES}"
                        DELETE_VPC="${params.DELETE_VPC}"

                        # Verify cluster exists
                        if ! rosa describe cluster --cluster="\${CLUSTER_NAME}" &>/dev/null; then
                            echo "Cluster '\${CLUSTER_NAME}' not found."
                            exit 0
                        fi

                        echo "Deleting cluster: \${CLUSTER_NAME}"

                        # Delete the cluster
                        rosa delete cluster --cluster="\${CLUSTER_NAME}" --yes --watch

                        # Optionally delete operator roles
                        if [ "\${DELETE_OPERATOR_ROLES}" = "true" ]; then
                            echo "Deleting operator roles for cluster: \${CLUSTER_NAME}"
                            rosa delete operator-roles --cluster="\${CLUSTER_NAME}" --yes --mode=auto || true
                        fi

                        # Delete VPC CloudFormation stack if requested
                        if [ "\${DELETE_VPC}" = "true" ]; then
                            VPC_STACK_NAME="\${CLUSTER_NAME}-vpc"
                            if aws cloudformation describe-stacks --stack-name "\${VPC_STACK_NAME}" --region "${REGION}" &>/dev/null; then
                                echo "Deleting VPC stack: \${VPC_STACK_NAME}"
                                aws cloudformation delete-stack --stack-name "\${VPC_STACK_NAME}" --region "${REGION}"
                                aws cloudformation wait stack-delete-complete --stack-name "\${VPC_STACK_NAME}" --region "${REGION}" || true
                            fi
                        fi

                        echo "Successfully deleted cluster: \${CLUSTER_NAME}"
                    """
                }
            }
        }

        stage('Delete All Clusters') {
            when { expression { env.ACTION == 'DELETE_ALL' } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh """
                        set -o errexit

                        DELETE_OPERATOR_ROLES="${params.DELETE_OPERATOR_ROLES}"
                        DELETE_VPC="${params.DELETE_VPC}"

                        # Get all PMM HA clusters
                        CLUSTERS_JSON=\$(rosa list clusters -o json 2>/dev/null || echo "[]")
                        CLUSTERS=\$(echo "\${CLUSTERS_JSON}" | jq -r --arg prefix "${CLUSTER_PREFIX}" '.[] | select(.name | startswith(\$prefix)) | .name')

                        if [ -z "\${CLUSTERS}" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        DELETED=0
                        FAILED=0

                        for CLUSTER_NAME in \${CLUSTERS}; do
                            echo "============================================"
                            echo "Deleting cluster: \${CLUSTER_NAME}"
                            echo "============================================"

                            if rosa delete cluster --cluster="\${CLUSTER_NAME}" --yes --watch; then
                                # Delete operator roles if requested
                                if [ "\${DELETE_OPERATOR_ROLES}" = "true" ]; then
                                    rosa delete operator-roles --cluster="\${CLUSTER_NAME}" --yes --mode=auto || true
                                fi

                                # Delete VPC stack if requested
                                if [ "\${DELETE_VPC}" = "true" ]; then
                                    VPC_STACK_NAME="\${CLUSTER_NAME}-vpc"
                                    if aws cloudformation describe-stacks --stack-name "\${VPC_STACK_NAME}" --region "${REGION}" &>/dev/null; then
                                        echo "Deleting VPC stack: \${VPC_STACK_NAME}"
                                        aws cloudformation delete-stack --stack-name "\${VPC_STACK_NAME}" --region "${REGION}"
                                        aws cloudformation wait stack-delete-complete --stack-name "\${VPC_STACK_NAME}" --region "${REGION}" || true
                                    fi
                                fi

                                DELETED=\$((DELETED + 1))
                            else
                                echo "Failed to delete cluster: \${CLUSTER_NAME}"
                                FAILED=\$((FAILED + 1))
                            fi
                        done

                        echo "============================================"
                        echo "DELETE ALL SUMMARY"
                        echo "============================================"
                        echo "Deleted: \${DELETED}"
                        echo "Failed: \${FAILED}"
                        echo "============================================"

                        if [ "\${FAILED}" -gt 0 ]; then
                            exit 1
                        fi
                    """
                }
            }
        }

        stage('Delete Old Clusters (cron only)') {
            when { expression { env.ACTION == 'DELETE_OLD' } }
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        set -o errexit

                        # Get all PMM HA clusters with creation timestamp
                        CLUSTERS_JSON=$(rosa list clusters -o json 2>/dev/null || echo "[]")

                        # Filter for PMM HA clusters
                        PMM_CLUSTERS=$(echo "${CLUSTERS_JSON}" | jq -r --arg prefix "${CLUSTER_PREFIX}" '
                            [.[] | select(.name | startswith($prefix))]
                        ')

                        CLUSTER_COUNT=$(echo "${PMM_CLUSTERS}" | jq 'length')

                        if [ "${CLUSTER_COUNT}" = "0" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        echo "Found ${CLUSTER_COUNT} PMM HA ROSA cluster(s)"

                        # Calculate cutoff time (24 hours ago)
                        CUTOFF_EPOCH=$(date -d "${MAX_AGE_HOURS} hours ago" +%s)
                        DELETED=0
                        SKIPPED=0
                        FAILED=0

                        # Process each cluster
                        echo "${PMM_CLUSTERS}" | jq -c '.[]' | while read -r CLUSTER; do
                            CLUSTER_NAME=$(echo "${CLUSTER}" | jq -r '.name')
                            CREATED=$(echo "${CLUSTER}" | jq -r '.creation_timestamp')

                            if [ -z "${CREATED}" ] || [ "${CREATED}" = "null" ]; then
                                echo "Unable to fetch creation time for ${CLUSTER_NAME} — skipping."
                                continue
                            fi

                            # Parse creation timestamp
                            CREATED_EPOCH=$(date -d "${CREATED}" +%s 2>/dev/null || echo "0")

                            if [ "${CREATED_EPOCH}" = "0" ]; then
                                echo "Unable to parse creation time for ${CLUSTER_NAME} — skipping."
                                continue
                            fi

                            AGE_HOURS=$(( ($(date +%s) - CREATED_EPOCH) / 3600 ))

                            if [ "${CREATED_EPOCH}" -lt "${CUTOFF_EPOCH}" ]; then
                                echo "============================================"
                                echo "Deleting old cluster: ${CLUSTER_NAME} (${AGE_HOURS}h old)"
                                echo "============================================"

                                if rosa delete cluster --cluster="${CLUSTER_NAME}" --yes --watch; then
                                    # Delete VPC stack (don't delete operator roles by default for safety)
                                    VPC_STACK_NAME="${CLUSTER_NAME}-vpc"
                                    if aws cloudformation describe-stacks --stack-name "${VPC_STACK_NAME}" --region "${REGION}" &>/dev/null; then
                                        echo "Deleting VPC stack: ${VPC_STACK_NAME}"
                                        aws cloudformation delete-stack --stack-name "${VPC_STACK_NAME}" --region "${REGION}"
                                        aws cloudformation wait stack-delete-complete --stack-name "${VPC_STACK_NAME}" --region "${REGION}" || true
                                    fi

                                    echo "Successfully deleted: ${CLUSTER_NAME}"
                                else
                                    echo "Failed to delete: ${CLUSTER_NAME}"
                                fi
                            else
                                echo "Skipping recent cluster: ${CLUSTER_NAME} (${AGE_HOURS}h old, within ${MAX_AGE_HOURS}h threshold)"
                            fi
                        done

                        echo "============================================"
                        echo "CLEANUP COMPLETE"
                        echo "============================================"
                    '''
                }
            }
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}

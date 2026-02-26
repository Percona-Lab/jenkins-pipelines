pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    triggers {
        cron('H 0,12 * * *') // Runs twice daily at 00:00 & 12:00
    }

    parameters {
        choice(
            name: 'ACTION',
            choices: ['LIST_ONLY', 'DELETE_CLUSTER', 'DELETE_ALL'],
            description: '''
                LIST_ONLY - list all test clusters<br/>
                DELETE_CLUSTER - delete a specific cluster (requires CLUSTER_NAME)<br/>
                DELETE_ALL - delete all test clusters<br/><br/>
                Note: Daily cron automatically deletes clusters older than 1 day.
            '''
        )
        string(name: 'CLUSTER_NAME', defaultValue: '', description: 'Cluster name(s) for DELETE_CLUSTER. Supports comma-separated values (e.g., pmm-ha-test-1,pmm-ha-test-2)')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    environment {
        REGION = "us-east-2"
        CLUSTER_PREFIX = "pmm-ha-test-"
    }

    stages {
        stage('Detect Run Type') {
            steps {
                script {
                    if (currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')) {
                        env.ACTION = 'DELETE_OLD'
                        echo "Triggered by cron - will delete clusters older than 1 day."
                    } else {
                        env.ACTION = params.ACTION
                        echo "Manual run with ACTION=${params.ACTION}"
                    }

                    if (env.ACTION == 'DELETE_CLUSTER' && !params.CLUSTER_NAME) {
                        error("CLUSTER_NAME is required for DELETE_CLUSTER.")
                    }
                    if (params.CLUSTER_NAME) {
                        params.CLUSTER_NAME.split(',').each { name ->
                            def trimmed = name.trim()
                            if (trimmed && !trimmed.startsWith(env.CLUSTER_PREFIX)) {
                                error("Cluster name '${trimmed}' must start with ${env.CLUSTER_PREFIX}")
                            }
                        }
                    }
                }
            }
        }

        stage('List Clusters') {
            when { expression { env.ACTION == 'LIST_ONLY' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x

                        CLUSTERS=$(aws eks list-clusters --region "$REGION" \
                            --query "clusters[?starts_with(@, '${CLUSTER_PREFIX}')]" \
                            --output text)

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        for c in $CLUSTERS; do
                            CREATED=$(aws eks describe-cluster \
                                --name "$c" --region "$REGION" \
                                --query "cluster.createdAt" --output text)

                            CREATED_EPOCH=$(date -d "$CREATED" +%s)
                            AGE_HOURS=$(( ( $(date +%s) - CREATED_EPOCH ) / 3600 ))

                            echo "• $c | Created: $CREATED | Age: ${AGE_HOURS}h"
                        done
                    '''
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { env.ACTION == 'DELETE_CLUSTER' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        IFS=',' read -ra CLUSTERS <<< "${CLUSTER_NAME}"
                        for c in "${CLUSTERS[@]}"; do
                            c=$(echo "$c" | xargs)  # trim whitespace
                            [ -z "$c" ] && continue

                            if ! aws eks describe-cluster --region "${REGION}" --name "$c" >/dev/null 2>&1; then
                                echo "Cluster '$c' not found in region '${REGION}' - skipping."
                                continue
                            fi

                            echo "Deleting cluster: $c"
                            eksctl delete cluster --region "${REGION}" --name "$c" \
                                --disable-nodegroup-eviction --wait
                        done
                    '''
                }
            }
        }

        stage('Delete All Clusters') {
            when { expression { env.ACTION == 'DELETE_ALL' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        CLUSTERS=$(aws eks list-clusters --region "$REGION" \
                            --query "clusters[?starts_with(@, '${CLUSTER_PREFIX}')]" --output text)

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        for c in $CLUSTERS; do
                            eksctl delete cluster --region "$REGION" --name "$c" \
                                --disable-nodegroup-eviction --wait
                        done
                    '''
                }
            }
        }

        stage('Delete Old Clusters (cron only)') {
            when { expression { env.ACTION == 'DELETE_OLD' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        CLUSTERS=$(aws eks list-clusters --region "$REGION" \
                            --query "clusters[?starts_with(@, '${CLUSTER_PREFIX}')]" \
                            --output text)

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        NOW_EPOCH=$(date +%s)

                        for c in $CLUSTERS; do
                            DESC=$(aws eks describe-cluster \
                                --name "$c" \
                                --region "$REGION" \
                                --output json)

                            CREATED=$(echo "$DESC" | jq -r '.cluster.createdAt')
                            RETENTION=$(echo "$DESC" | jq -r '.cluster.tags["retention-days"]')

                            CREATED_EPOCH=$(date -d "$CREATED" +%s)
                            MAX_AGE_SECONDS=$(( RETENTION * 86400 ))
                            AGE_SECONDS=$(( NOW_EPOCH - CREATED_EPOCH ))

                            if [ "$AGE_SECONDS" -gt "$MAX_AGE_SECONDS" ]; then
                                eksctl delete cluster \
                                    --region "$REGION" \
                                    --name "$c" \
                                    --disable-nodegroup-eviction \
                                    --wait
                            else
                                echo "Keeping cluster $c (age < ${RETENTION} days)"
                            fi
                        done
                    '''
                }
            }
        }
    }
}

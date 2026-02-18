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
                LIST_ONLY - List all PMM OpenShift test clusters<br/>
                DELETE_CLUSTER - Delete specific cluster(s) (requires CLUSTER_NAME)<br/>
                DELETE_ALL - Delete all PMM OpenShift test clusters<br/><br/>
                Note: Cron automatically deletes clusters past their retention period.
            '''
        )
        string(
            name: 'CLUSTER_NAME',
            defaultValue: '',
            description: 'Cluster name(s) for DELETE_CLUSTER. Supports comma-separated (e.g., pmm-ha-openshift-1,pmm-ha-openshift-2)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
    }

    environment {
        REGION = "us-east-2"
        CLUSTER_PREFIX = "pmm-ha-openshift-"
    }

    stages {
        stage('Detect Run Type') {
            steps {
                script {
                    if (currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')) {
                        env.ACTION = 'DELETE_OLD'
                        echo "Triggered by cron - will delete clusters past retention period."
                    } else {
                        env.ACTION = params.ACTION
                        echo "Manual run with ACTION=${params.ACTION}"
                    }

                    if (env.ACTION == 'DELETE_CLUSTER' && !params.CLUSTER_NAME) {
                        error("CLUSTER_NAME is required for DELETE_CLUSTER action.")
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
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        set +x

                        rosa login --token="${ROSA_TOKEN}"

                        CLUSTERS=$(rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'"))')

                        if [ -z "$CLUSTERS" ] || [ "$CLUSTERS" = "null" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        echo "====================================================================="
                        echo "PMM OPENSHIFT TEST CLUSTERS"
                        echo "====================================================================="
                        echo ""

                        rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'"))' | jq -s '.' | while read -r cluster; do
                            echo "$cluster" | jq -r '"Cluster: \\(.name)\\n  State: \\(.state)\\n  Version: \\(.openshift_version)\\n  Created: \\(.creation_timestamp)\\n"'
                        done

                        # Show detailed info for each cluster
                        for name in $(rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'")) | .name'); do
                            echo "---"
                            echo "Cluster: $name"

                            CLUSTER_INFO=$(rosa describe cluster --cluster="$name" --region="${REGION}" -o json 2>/dev/null)
                            if [ -n "$CLUSTER_INFO" ]; then
                                STATE=$(echo "$CLUSTER_INFO" | jq -r '.state')
                                VERSION=$(echo "$CLUSTER_INFO" | jq -r '.openshift_version')
                                CREATED=$(echo "$CLUSTER_INFO" | jq -r '.creation_timestamp')
                                CONSOLE=$(echo "$CLUSTER_INFO" | jq -r '.console.url // "N/A"')

                                # Calculate age
                                if [ -n "$CREATED" ] && [ "$CREATED" != "null" ]; then
                                    CREATED_EPOCH=$(date -d "$CREATED" +%s 2>/dev/null || echo "0")
                                    NOW_EPOCH=$(date +%s)
                                    AGE_HOURS=$(( (NOW_EPOCH - CREATED_EPOCH) / 3600 ))
                                else
                                    AGE_HOURS="Unknown"
                                fi

                                echo "  State:     $STATE"
                                echo "  Version:   $VERSION"
                                echo "  Created:   $CREATED"
                                echo "  Age:       ${AGE_HOURS} hours"
                                echo "  Console:   $CONSOLE"
                            fi
                            echo ""
                        done
                    '''
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { env.ACTION == 'DELETE_CLUSTER' } }
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        sh 'rosa login --token="${ROSA_TOKEN}"'

                        params.CLUSTER_NAME.split(',').each { name ->
                            def clusterName = name.trim()
                            if (!clusterName) return

                            echo "Processing cluster: ${clusterName}"

                            def exists = sh(
                                returnStatus: true,
                                script: "rosa describe cluster --cluster='${clusterName}' --region='${REGION}' &>/dev/null"
                            ) == 0

                            if (!exists) {
                                echo "Cluster '${clusterName}' not found - skipping."
                                return
                            }

                            echo "Deleting cluster: ${clusterName}"
                            sh """
                                rosa delete cluster --cluster='${clusterName}' --region='${REGION}' --yes --watch || true
                            """
                            echo "Cluster ${clusterName} deletion initiated."
                        }
                    }
                }
            }
        }

        stage('Delete All Clusters') {
            when { expression { env.ACTION == 'DELETE_ALL' } }
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        rosa login --token="${ROSA_TOKEN}"

                        CLUSTERS=$(rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'")) | .name')

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        for name in $CLUSTERS; do
                            echo "Deleting cluster: $name"
                            rosa delete cluster --cluster="$name" --region="${REGION}" --yes --watch || true
                            echo "Cluster $name deletion initiated."
                        done
                    '''
                }
            }
        }

        stage('Delete Old Clusters (cron)') {
            when { expression { env.ACTION == 'DELETE_OLD' } }
            steps {
                withCredentials([
                    aws(credentialsId: 'pmm-staging-slave'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        rosa login --token="${ROSA_TOKEN}"

                        CLUSTERS=$(rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'")) | .name')

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        NOW_EPOCH=$(date +%s)

                        for name in $CLUSTERS; do
                            echo "Checking cluster: $name"

                            CLUSTER_INFO=$(rosa describe cluster --cluster="$name" --region="${REGION}" -o json 2>/dev/null)
                            if [ -z "$CLUSTER_INFO" ]; then
                                echo "  Could not get cluster info - skipping"
                                continue
                            fi

                            CREATED=$(echo "$CLUSTER_INFO" | jq -r '.creation_timestamp')

                            # Get retention-days from ROSA cluster tags
                            RETENTION=$(echo "$CLUSTER_INFO" | jq -r '.aws.tags["retention-days"] // empty')

                            if [ -z "$RETENTION" ] || [ "$RETENTION" = "null" ]; then
                                # Fallback: default to 1 day if tag not found
                                RETENTION=1
                                echo "  Warning: retention-days tag not found, defaulting to 1 day"
                            fi

                            if [ -n "$CREATED" ] && [ "$CREATED" != "null" ]; then
                                CREATED_EPOCH=$(date -d "$CREATED" +%s 2>/dev/null || echo "0")
                                MAX_AGE_SECONDS=$(( RETENTION * 86400 ))
                                AGE_SECONDS=$(( NOW_EPOCH - CREATED_EPOCH ))
                                AGE_HOURS=$(( AGE_SECONDS / 3600 ))

                                if [ "$AGE_SECONDS" -gt "$MAX_AGE_SECONDS" ]; then
                                    echo "  Cluster $name is past retention (${RETENTION} days, age: ${AGE_HOURS}h) - deleting..."
                                    rosa delete cluster --cluster="$name" --region="${REGION}" --yes --watch || true
                                    echo "  Cluster $name deletion initiated."
                                else
                                    echo "  Keeping cluster $name (age: ${AGE_HOURS}h < ${RETENTION} days retention)"
                                fi
                            else
                                echo "  Could not determine age - skipping"
                            fi
                        done
                    '''
                }
            }
        }
    }

    post {
        always {
            script {
                def clusterCount = 0
                try {
                    withCredentials([
                        string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                    ]) {
                        sh 'rosa login --token="${ROSA_TOKEN}"'
                        clusterCount = sh(
                            returnStdout: true,
                            script: '''
                                rosa list clusters --region="${REGION}" -o json 2>/dev/null | jq -r '[.[] | select(.name | startswith("pmm-ha-openshift-"))] | length' || echo "0"
                            '''
                        ).trim().toInteger()
                    }
                } catch (Exception e) {
                    clusterCount = 0
                }
                currentBuild.description = "Action: ${env.ACTION} | Clusters: ${clusterCount}"
            }
        }
    }
}

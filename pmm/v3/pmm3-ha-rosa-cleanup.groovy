pipeline {
    agent {
        label 'agent-amd64'
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
            description: 'Cluster name(s) for DELETE_CLUSTER. Supports comma-separated (e.g., pmm-ha-rosa-1,pmm-ha-rosa-2)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
    }

    environment {
        REGION = "us-east-2"
        CLUSTER_PREFIX = "pmm-ha-rosa-"
        PATH = "${HOME}/.local/bin:${PATH}"
    }

    stages {
        stage('Install Tools') {
            steps {
                withCredentials([string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')]) {
                    sh '''
                        mkdir -p $HOME/.local/bin
                        export PATH="$HOME/.local/bin:$PATH"

                        # Install ROSA CLI
                        echo "Installing ROSA CLI..."
                        curl -sL https://mirror.openshift.com/pub/openshift-v4/clients/rosa/latest/rosa-linux.tar.gz -o rosa.tar.gz
                        tar xzf rosa.tar.gz
                        mv rosa $HOME/.local/bin/
                        chmod +x $HOME/.local/bin/rosa
                        rm -f rosa.tar.gz

                        rosa version

                        # Login once for the entire pipeline
                        rosa login --token="${ROSA_TOKEN}"
                    '''
                }
            }
        }

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
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x

                        CLUSTERS=$(rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'"))')

                        if [ -z "$CLUSTERS" ] || [ "$CLUSTERS" = "null" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        echo "====================================================================="
                        echo "PMM OPENSHIFT TEST CLUSTERS"
                        echo "====================================================================="
                        echo ""

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

        stage('Delete Clusters') {
            when { expression { env.ACTION in ['DELETE_CLUSTER', 'DELETE_ALL', 'DELETE_OLD'] } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        export AWS_RETRY_MODE=adaptive
                        export AWS_MAX_ATTEMPTS=10

                        VPC_IDS=$(aws ec2 describe-vpcs --region "${REGION}" \
                            --filters "Name=tag:Name,Values=pmm-ha-rosa-shared-vpc" "Name=state,Values=available" \
                            --query 'Vpcs[].VpcId' --output text)
                        VPC_COUNT=$(echo $VPC_IDS | wc -w)
                        if [ "$VPC_COUNT" -ne 1 ]; then
                            echo "Expected exactly 1 VPC matching pmm-ha-rosa-shared-vpc, found $VPC_COUNT: $VPC_IDS"
                            exit 1
                        fi
                        VPC_ID="$VPC_IDS"

                        # Delete a single cluster and sweep AWS resources tied to its ID
                        delete_cluster() {
                            local name="$1"
                            local info cluster_id oidc_id
                            info=$(rosa describe cluster --cluster="$name" --region="${REGION}" -o json 2>/dev/null || echo "")
                            cluster_id=$(echo "$info" | jq -r '.id // empty' 2>/dev/null || true)
                            oidc_id=$(echo "$info" | jq -r '.aws.sts.oidc_config.id // empty' 2>/dev/null || true)

                            echo "Deleting cluster: $name (ID: ${cluster_id:-unknown})"
                            rosa delete cluster --cluster="$name" --region="${REGION}" --yes --watch || true
                            rosa delete operator-roles --prefix "$name" --region "${REGION}" --mode auto --yes || true

                            if [ -n "$oidc_id" ]; then
                                rosa delete oidc-provider --oidc-config-id "$oidc_id" --region "${REGION}" --mode auto --yes || true
                                rosa delete oidc-config --oidc-config-id "$oidc_id" --region "${REGION}" --mode auto --yes || true
                            fi

                            if [ -n "$cluster_id" ]; then
                                echo "  Sweeping AWS resources for cluster ID $cluster_id in VPC $VPC_ID"

                                # Strip cluster tags from subnets tagged for this cluster (batched into one call)
                                SUBNETS=$(aws ec2 describe-subnets --region "${REGION}" \
                                    --filters "Name=vpc-id,Values=$VPC_ID" \
                                              "Name=tag:kubernetes.io/cluster/${cluster_id},Values=owned,shared" \
                                    --query 'Subnets[].SubnetId' --output text)
                                if [ -n "$SUBNETS" ]; then
                                    aws ec2 delete-tags --region "${REGION}" \
                                        --resources $SUBNETS --tags Key="kubernetes.io/cluster/${cluster_id}"
                                fi

                                # Delete VPC endpoints (batched) before SG cleanup
                                VPCES=$(aws ec2 describe-vpc-endpoints --region "${REGION}" \
                                    --filters "Name=vpc-id,Values=$VPC_ID" \
                                              "Name=tag:kubernetes.io/cluster/${cluster_id},Values=owned" \
                                    --query 'VpcEndpoints[].VpcEndpointId' --output text)
                                if [ -n "$VPCES" ]; then
                                    echo "    Deleting VPC endpoints: $VPCES"
                                    aws ec2 delete-vpc-endpoints --region "${REGION}" --vpc-endpoint-ids $VPCES
                                fi

                                # Two-pass SG cleanup: revoke all rules first to break cross-references, then delete
                                SGS=$(aws ec2 describe-security-groups --region "${REGION}" \
                                    --filters "Name=vpc-id,Values=$VPC_ID" "Name=group-name,Values=${cluster_id}-*" \
                                    --query 'SecurityGroups[].GroupId' --output text)

                                for sg in $SGS; do
                                    INGRESS=$(aws ec2 describe-security-groups --region "${REGION}" --group-ids "$sg" \
                                        --query 'SecurityGroups[0].IpPermissions' --output json)
                                    EGRESS=$(aws ec2 describe-security-groups --region "${REGION}" --group-ids "$sg" \
                                        --query 'SecurityGroups[0].IpPermissionsEgress' --output json)
                                    [ "$INGRESS" != "[]" ] && aws ec2 revoke-security-group-ingress --region "${REGION}" \
                                        --group-id "$sg" --ip-permissions "$INGRESS"
                                    [ "$EGRESS" != "[]" ] && aws ec2 revoke-security-group-egress --region "${REGION}" \
                                        --group-id "$sg" --ip-permissions "$EGRESS"
                                done

                                for sg in $SGS; do
                                    echo "    Deleting SG $sg"
                                    aws ec2 delete-security-group --region "${REGION}" --group-id "$sg"
                                done
                            fi

                            echo "Cleanup completed for: $name"
                        }

                        # Decide which clusters to process based on action
                        TARGETS=""
                        case "${ACTION}" in
                            DELETE_CLUSTER)
                                TARGETS="${CLUSTER_NAME//,/ }"
                                ;;
                            DELETE_ALL)
                                TARGETS=$(rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'")) | .name')
                                ;;
                            DELETE_OLD)
                                NOW_EPOCH=$(date +%s)
                                for name in $(rosa list clusters --region="${REGION}" -o json | jq -r '.[] | select(.name | startswith("'"${CLUSTER_PREFIX}"'")) | .name'); do
                                    info=$(rosa describe cluster --cluster="$name" --region="${REGION}" -o json 2>/dev/null)
                                    [ -z "$info" ] && { echo "$name: cannot describe - skipping"; continue; }
                                    created=$(echo "$info" | jq -r '.creation_timestamp')
                                    retention=$(echo "$info" | jq -r '.aws.tags["retention-days"] // "1"')
                                    [ "$retention" = "null" ] && retention=1
                                    [ -z "$created" ] || [ "$created" = "null" ] && { echo "$name: no creation_timestamp - skipping"; continue; }
                                    created_epoch=$(date -d "$created" +%s 2>/dev/null || echo "0")
                                    age_h=$(( (NOW_EPOCH - created_epoch) / 3600 ))
                                    if [ $(( NOW_EPOCH - created_epoch )) -gt $(( retention * 86400 )) ]; then
                                        echo "$name: age ${age_h}h > retention ${retention}d - will delete"
                                        TARGETS="$TARGETS $name"
                                    else
                                        echo "$name: age ${age_h}h < retention ${retention}d - keeping"
                                    fi
                                done
                                ;;
                        esac

                        if [ -z "${TARGETS// /}" ]; then
                            echo "No clusters to delete."
                            exit 0
                        fi

                        for name in $TARGETS; do
                            delete_cluster "$name"
                        done
                    '''
                }
            }
        }

    }
}

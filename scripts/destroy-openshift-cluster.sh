#!/bin/bash
#
# OpenShift Cluster Destroyer Script
#
# This script can destroy OpenShift clusters in various states:
# - Properly installed clusters with metadata.json
# - Orphaned clusters without state files
# - Partially created clusters that failed during installation
#
# Usage: ./destroy-openshift-cluster.sh [OPTIONS]
#
# Required parameters (one of):
#   --cluster-name NAME     Base cluster name (will auto-detect infra-id)
#   --infra-id ID          Infrastructure ID (e.g., cluster-name-xxxxx)
#   --metadata-file PATH   Path to metadata.json file
#
# Optional parameters:
#   --region REGION        AWS region (default: us-east-2)
#   --profile PROFILE      AWS profile (default: percona-dev-admin)
#   --base-domain DOMAIN   Base domain for Route53 (default: cd.percona.com)
#   --dry-run             Show what would be deleted without actually deleting
#   --force               Skip confirmation prompts
#   --verbose             Enable verbose output
#   --s3-bucket BUCKET    S3 bucket for state files (auto-detected if not provided)
#   --help                Show this help message

set -euo pipefail

# Default values
AWS_REGION="${AWS_REGION:-us-east-2}"
AWS_PROFILE="${AWS_PROFILE:-percona-dev-admin}"
BASE_DOMAIN="${BASE_DOMAIN:-cd.percona.com}"
DRY_RUN=false
FORCE=false
VERBOSE=false
CLUSTER_NAME=""
INFRA_ID=""
METADATA_FILE=""
S3_BUCKET=""
LOG_FILE="/tmp/openshift-destroy-$(date +%Y%m%d-%H%M%S).log"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Logging functions
log() {
    echo -e "${1}" | tee -a "$LOG_FILE"
}

log_info() {
    log "${BLUE}[INFO]${NC} ${1}"
}

log_success() {
    log "${GREEN}[SUCCESS]${NC} ${1}"
}

log_warning() {
    log "${YELLOW}[WARNING]${NC} ${1}"
}

log_error() {
    log "${RED}[ERROR]${NC} ${1}"
}

log_debug() {
    if [[ "$VERBOSE" == "true" ]]; then
        log "[DEBUG] ${1}"
    fi
}

# Help function
show_help() {
    cat << EOF
OpenShift Cluster Destroyer Script

This script safely removes OpenShift clusters and all associated AWS resources.

USAGE:
    $(basename "$0") [OPTIONS]

REQUIRED (one of):
    --cluster-name NAME     Base cluster name (will auto-detect infra-id)
    --infra-id ID          Infrastructure ID (e.g., cluster-name-xxxxx)
    --metadata-file PATH   Path to metadata.json file

OPTIONS:
    --region REGION        AWS region (default: us-east-2)
    --profile PROFILE      AWS profile (default: percona-dev-admin)
    --base-domain DOMAIN   Base domain for Route53 (default: cd.percona.com)
    --dry-run             Show what would be deleted without actually deleting
    --force               Skip confirmation prompts
    --verbose             Enable verbose output
    --s3-bucket BUCKET    S3 bucket for state files (auto-detected if not provided)
    --help                Show this help message

EXAMPLES:
    # Destroy using cluster name (auto-detects infra-id)
    $(basename "$0") --cluster-name helm-test

    # Destroy using specific infrastructure ID
    $(basename "$0") --infra-id helm-test-tqtlx

    # Dry run to see what would be deleted
    $(basename "$0") --cluster-name test-cluster --dry-run

    # Destroy using metadata file
    $(basename "$0") --metadata-file /path/to/metadata.json

    # Force deletion without prompts
    $(basename "$0") --infra-id helm-test-tqtlx --force

NOTES:
    - The script will attempt to use openshift-install if metadata exists
    - Falls back to manual AWS resource deletion for orphaned clusters
    - All operations are logged to: $LOG_FILE

EOF
    exit 0
}

# Parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --cluster-name)
                CLUSTER_NAME="$2"
                shift 2
                ;;
            --infra-id)
                INFRA_ID="$2"
                shift 2
                ;;
            --metadata-file)
                METADATA_FILE="$2"
                shift 2
                ;;
            --region)
                AWS_REGION="$2"
                shift 2
                ;;
            --profile)
                AWS_PROFILE="$2"
                shift 2
                ;;
            --base-domain)
                BASE_DOMAIN="$2"
                shift 2
                ;;
            --s3-bucket)
                S3_BUCKET="$2"
                shift 2
                ;;
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            --force)
                FORCE=true
                shift
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --help|-h)
                show_help
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                ;;
        esac
    done
}

# Validate inputs
validate_inputs() {
    # Check if at least one identifier is provided
    if [[ -z "$CLUSTER_NAME" && -z "$INFRA_ID" && -z "$METADATA_FILE" ]]; then
        log_error "You must provide either --cluster-name, --infra-id, or --metadata-file"
        show_help
    fi

    # Check AWS credentials
    if ! aws sts get-caller-identity --profile "$AWS_PROFILE" &>/dev/null; then
        log_error "Failed to authenticate with AWS profile: $AWS_PROFILE"
        log_info "Try running: aws sso login --profile $AWS_PROFILE"
        exit 1
    fi

    # Auto-detect S3 bucket if not provided
    if [[ -z "$S3_BUCKET" ]]; then
        local account_id=$(aws sts get-caller-identity --profile "$AWS_PROFILE" --query Account --output text)
        S3_BUCKET="openshift-clusters-${account_id}-${AWS_REGION}"
        log_debug "Auto-detected S3 bucket: $S3_BUCKET"
    fi
}

# Extract metadata from file
extract_metadata() {
    local metadata_file="$1"

    if [[ -f "$metadata_file" ]]; then
        INFRA_ID=$(jq -r '.infraID' "$metadata_file" 2>/dev/null || echo "")
        CLUSTER_NAME=$(jq -r '.clusterName' "$metadata_file" 2>/dev/null || echo "")
        AWS_REGION=$(jq -r '.aws.region // .platform.aws.region' "$metadata_file" 2>/dev/null || echo "$AWS_REGION")

        if [[ -n "$INFRA_ID" ]]; then
            log_info "Extracted from metadata: cluster=$CLUSTER_NAME, infra-id=$INFRA_ID, region=$AWS_REGION"
            return 0
        fi
    fi

    return 1
}

# Auto-detect infrastructure ID from AWS resources
detect_infra_id() {
    local cluster_name="$1"

    log_info "Searching for infrastructure ID for cluster: $cluster_name"

    # Search for VPCs with cluster tags
    local vpc_tags=$(aws ec2 describe-vpcs \
        --filters "Name=tag-key,Values=kubernetes.io/cluster/${cluster_name}*" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[].Tags[?starts_with(Key, 'kubernetes.io/cluster/')].Key" \
        --output text 2>/dev/null)

    if [[ -n "$vpc_tags" ]]; then
        # Extract infra ID from tag
        INFRA_ID=$(echo "$vpc_tags" | sed 's/kubernetes.io\/cluster\///' | head -1)
        log_success "Auto-detected infrastructure ID: $INFRA_ID"
        return 0
    fi

    # Try S3 metadata
    if aws s3 ls "s3://${S3_BUCKET}/${cluster_name}/metadata.json" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then

        local temp_metadata="/tmp/${cluster_name}-metadata.json"
        aws s3 cp "s3://${S3_BUCKET}/${cluster_name}/metadata.json" "$temp_metadata" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null

        if extract_metadata "$temp_metadata"; then
            rm -f "$temp_metadata"
            return 0
        fi
        rm -f "$temp_metadata"
    fi

    log_warning "Could not auto-detect infrastructure ID for cluster: $cluster_name"
    return 1
}

# Count AWS resources for a cluster
count_resources() {
    local infra_id="$1"
    local resource_count=0

    # Log to stderr so it doesn't interfere with return value
    log_info "Counting resources for infrastructure ID: $infra_id" >&2

    # EC2 Instances
    local instances=$(aws ec2 describe-instances \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
                  "Name=instance-state-name,Values=running,stopped,stopping,pending" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Reservations[].Instances[].InstanceId" --output text 2>/dev/null | wc -w)
    ((resource_count += instances))
    [[ $instances -gt 0 ]] && log_info "  EC2 Instances: $instances" >&2

    # Load Balancers
    local elbs=$(aws elb describe-load-balancers \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "LoadBalancerDescriptions[?contains(LoadBalancerName, '$infra_id')].LoadBalancerName" \
        --output text 2>/dev/null | wc -w)
    ((resource_count += elbs))
    [[ $elbs -gt 0 ]] && log_info "  Classic Load Balancers: $elbs" >&2

    local nlbs=$(aws elbv2 describe-load-balancers \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "LoadBalancers[?contains(LoadBalancerName, '$infra_id')].LoadBalancerArn" \
        --output text 2>/dev/null | wc -w)
    ((resource_count += nlbs))
    [[ $nlbs -gt 0 ]] && log_info "  Network/Application Load Balancers: $nlbs" >&2

    # NAT Gateways
    local nats=$(aws ec2 describe-nat-gateways \
        --filter "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "NatGateways[?State!='deleted'].NatGatewayId" --output text 2>/dev/null | wc -w)
    ((resource_count += nats))
    [[ $nats -gt 0 ]] && log_info "  NAT Gateways: $nats" >&2

    # Elastic IPs
    local eips=$(aws ec2 describe-addresses \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Addresses[].AllocationId" --output text 2>/dev/null | wc -w)
    ((resource_count += eips))
    [[ $eips -gt 0 ]] && log_info "  Elastic IPs: $eips" >&2

    # VPCs and their nested resources
    local vpcs=$(aws ec2 describe-vpcs \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[].VpcId" --output text 2>/dev/null | wc -w)
    
    if [[ $vpcs -gt 0 ]]; then
        local vpc_id=$(aws ec2 describe-vpcs \
            --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Vpcs[0].VpcId" --output text 2>/dev/null)
        
        if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
            # Count VPC itself
            ((resource_count += 1))
            log_info "  VPCs: 1" >&2
            
            # Count subnets
            local subnet_count=$(aws ec2 describe-subnets \
                --filters "Name=vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "Subnets | length(@)" --output text 2>/dev/null || echo 0)
            ((resource_count += subnet_count))
            [[ $subnet_count -gt 0 ]] && log_info "    Subnets: $subnet_count" >&2
            
            # Count security groups (excluding default)
            local sg_count=$(aws ec2 describe-security-groups \
                --filters "Name=vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "SecurityGroups[?GroupName!='default'] | length(@)" --output text 2>/dev/null || echo 0)
            ((resource_count += sg_count))
            [[ $sg_count -gt 0 ]] && log_info "    Security Groups: $sg_count" >&2
            
            # Count route tables (excluding main)
            local rt_count=$(aws ec2 describe-route-tables \
                --filters "Name=vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "RouteTables[?Associations[0].Main!=\`true\`] | length(@)" --output text 2>/dev/null || echo 0)
            ((resource_count += rt_count))
            [[ $rt_count -gt 0 ]] && log_info "    Route Tables: $rt_count" >&2
            
            # Count Internet Gateways
            local igw_count=$(aws ec2 describe-internet-gateways \
                --filters "Name=attachment.vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "InternetGateways | length(@)" --output text 2>/dev/null || echo 0)
            ((resource_count += igw_count))
            [[ $igw_count -gt 0 ]] && log_info "    Internet Gateways: $igw_count" >&2
        fi
    fi

    echo "$resource_count"
}

# Try to destroy using openshift-install
destroy_with_openshift_install() {
    local cluster_dir="$1"

    log_info "Attempting destruction with openshift-install..."

    # Check if openshift-install is available
    if ! command -v openshift-install &> /dev/null; then
        log_warning "openshift-install not found in PATH"
        return 1
    fi

    # Check if metadata.json exists
    if [[ ! -f "${cluster_dir}/metadata.json" ]]; then
        log_warning "No metadata.json found in $cluster_dir"
        return 1
    fi

    if [[ "$DRY_RUN" == "true" ]]; then
        log_info "[DRY RUN] Would run: openshift-install destroy cluster --dir=$cluster_dir"
        return 0
    fi

    # Run openshift-install destroy
    cd "$cluster_dir"
    if AWS_PROFILE="$AWS_PROFILE" openshift-install destroy cluster --log-level=info 2>&1 | tee -a "$LOG_FILE"; then
        log_success "Successfully destroyed cluster using openshift-install"
        return 0
    else
        log_warning "openshift-install destroy failed, falling back to manual cleanup"
        return 1
    fi
}

# Clean up Route53 DNS records
cleanup_route53_records() {
    local infra_id="$1"
    local cluster_name="${CLUSTER_NAME:-${infra_id%-*}}"
    local base_domain="${BASE_DOMAIN:-cd.percona.com}"

    log_info "  Checking Route53 DNS records..."
    log_debug "Looking for: api.$cluster_name.$base_domain and *.apps.$cluster_name.$base_domain"

    # Get hosted zone ID
    local zone_id=$(aws route53 list-hosted-zones \
        --query "HostedZones[?Name=='${base_domain}.'].Id" \
        --output text --profile "$AWS_PROFILE" 2>/dev/null | head -1)

    if [[ -z "$zone_id" ]]; then
        log_debug "No hosted zone found for domain: $base_domain"
        return 0
    fi

    # Look for DNS records related to the cluster
    # Check both api. and *.apps. patterns
    local api_record=$(aws route53 list-resource-record-sets \
        --hosted-zone-id "$zone_id" \
        --query "ResourceRecordSets[?Name=='api.${cluster_name}.${base_domain}.']" \
        --profile "$AWS_PROFILE" 2>/dev/null)

    local apps_record=$(aws route53 list-resource-record-sets \
        --hosted-zone-id "$zone_id" \
        --query "ResourceRecordSets[?Name=='\\052.apps.${cluster_name}.${base_domain}.']" \
        --profile "$AWS_PROFILE" 2>/dev/null)

    local found_records=false

    # Check if we found any records
    if [[ "$api_record" != "[]" && "$api_record" != "null" ]]; then
        found_records=true
    fi
    if [[ "$apps_record" != "[]" && "$apps_record" != "null" ]]; then
        found_records=true
    fi

    if [[ "$found_records" == "false" ]]; then
        log_info "  No Route53 records found for cluster"
        return 0
    fi

    log_info "  Found Route53 DNS records to clean up"

    # Process API record if found
    if [[ "$api_record" != "[]" && "$api_record" != "null" ]]; then
        echo "$api_record" | jq -c '.[]' | while read -r record; do
            local name=$(echo "$record" | jq -r '.Name')
            local type=$(echo "$record" | jq -r '.Type')

            if [[ "$DRY_RUN" == "false" ]]; then
                # Create change batch for deletion
                local change_batch=$(cat <<EOF
{
    "Changes": [{
        "Action": "DELETE",
        "ResourceRecordSet": $record
    }]
}
EOF
                )

                # Apply the change
                aws route53 change-resource-record-sets \
                    --hosted-zone-id "$zone_id" \
                    --change-batch "$change_batch" \
                    --profile "$AWS_PROFILE" >/dev/null 2>&1 || true

                log_info "    Deleted DNS record: $name ($type)"
            else
                log_info "    [DRY RUN] Would delete DNS record: $name ($type)"
            fi
        done
    fi

    # Process apps wildcard record if found
    if [[ "$apps_record" != "[]" && "$apps_record" != "null" ]]; then
        echo "$apps_record" | jq -c '.[]' | while read -r record; do
            local name=$(echo "$record" | jq -r '.Name')
            local type=$(echo "$record" | jq -r '.Type')

            if [[ "$DRY_RUN" == "false" ]]; then
                # Create change batch for deletion
                local change_batch=$(cat <<EOF
{
    "Changes": [{
        "Action": "DELETE",
        "ResourceRecordSet": $record
    }]
}
EOF
                )

                # Apply the change
                aws route53 change-resource-record-sets \
                    --hosted-zone-id "$zone_id" \
                    --change-batch "$change_batch" \
                    --profile "$AWS_PROFILE" >/dev/null 2>&1 || true

                log_info "    Deleted DNS record: $name ($type)"
            else
                log_info "    [DRY RUN] Would delete DNS record: $name ($type)"
            fi
        done
    fi
}

# Manual AWS resource cleanup
destroy_aws_resources() {
    local infra_id="$1"

    log_info "Starting manual AWS resource cleanup for: $infra_id"

    if [[ "$DRY_RUN" == "true" ]]; then
        log_warning "DRY RUN MODE - No resources will be deleted"
    fi

    # 1. Terminate EC2 Instances
    log_info "Step 1/9: Terminating EC2 instances..."
    local instance_ids=$(aws ec2 describe-instances \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
                  "Name=instance-state-name,Values=running,stopped,stopping,pending" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Reservations[].Instances[].InstanceId" --output text)

    if [[ -n "$instance_ids" ]]; then
        if [[ "$DRY_RUN" == "false" ]]; then
            aws ec2 terminate-instances --instance-ids $instance_ids \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null
            log_info "  Waiting for instances to terminate..."
            aws ec2 wait instance-terminated --instance-ids $instance_ids \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
        else
            log_info "  [DRY RUN] Would terminate: $instance_ids"
        fi
    else
        log_info "  No instances found"
    fi

    # 2. Delete Load Balancers
    log_info "Step 2/9: Deleting load balancers..."

    # Classic ELBs
    local elbs=$(aws elb describe-load-balancers \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "LoadBalancerDescriptions[?contains(LoadBalancerName, '$infra_id')].LoadBalancerName" \
        --output text)

    for elb in $elbs; do
        if [[ "$DRY_RUN" == "false" ]]; then
            aws elb delete-load-balancer --load-balancer-name "$elb" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE"
            log_info "  Deleted Classic ELB: $elb"
        else
            log_info "  [DRY RUN] Would delete Classic ELB: $elb"
        fi
    done

    # ALBs/NLBs
    local nlbs=$(aws elbv2 describe-load-balancers \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "LoadBalancers[?contains(LoadBalancerName, '$infra_id')].LoadBalancerArn" \
        --output text)

    for nlb in $nlbs; do
        if [[ "$DRY_RUN" == "false" ]]; then
            aws elbv2 delete-load-balancer --load-balancer-arn "$nlb" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE"
            log_info "  Deleted NLB/ALB: $(basename $nlb)"
        else
            log_info "  [DRY RUN] Would delete NLB/ALB: $(basename $nlb)"
        fi
    done

    # 3. Delete NAT Gateways
    log_info "Step 3/9: Deleting NAT gateways..."
    local nat_gateways=$(aws ec2 describe-nat-gateways \
        --filter "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "NatGateways[?State!='deleted'].NatGatewayId" --output text)

    for nat_id in $nat_gateways; do
        if [[ "$DRY_RUN" == "false" ]]; then
            aws ec2 delete-nat-gateway --nat-gateway-id "$nat_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null
            log_info "  Deleted NAT Gateway: $nat_id"
        else
            log_info "  [DRY RUN] Would delete NAT Gateway: $nat_id"
        fi
    done

    # 4. Release Elastic IPs
    log_info "Step 4/9: Releasing Elastic IPs..."
    local eips=$(aws ec2 describe-addresses \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Addresses[].AllocationId" --output text)

    for eip in $eips; do
        if [[ "$DRY_RUN" == "false" ]]; then
            aws ec2 release-address --allocation-id "$eip" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
            log_info "  Released Elastic IP: $eip"
        else
            log_info "  [DRY RUN] Would release Elastic IP: $eip"
        fi
    done

    # 5. Delete Security Groups (wait a bit for dependencies to clear)
    if [[ "$DRY_RUN" == "false" ]]; then
        log_info "  Waiting for network interfaces to detach..."
        sleep 30
    fi

    log_info "Step 5/9: Deleting security groups..."
    local vpc_id=$(aws ec2 describe-vpcs \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[0].VpcId" --output text)

    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        local sgs=$(aws ec2 describe-security-groups \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "SecurityGroups[?GroupName!='default'].GroupId" --output text)

        # Delete rules first to avoid dependency issues
        for sg in $sgs; do
            if [[ "$DRY_RUN" == "false" ]]; then
                # Remove all ingress rules
                aws ec2 revoke-security-group-ingress --group-id "$sg" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                    --source-group "$sg" --protocol all 2>/dev/null || true
            fi
        done

        # Now delete the security groups
        for sg in $sgs; do
            if [[ "$DRY_RUN" == "false" ]]; then
                aws ec2 delete-security-group --group-id "$sg" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
                log_info "  Deleted Security Group: $sg"
            else
                log_info "  [DRY RUN] Would delete Security Group: $sg"
            fi
        done
    fi

    # 6. Delete Subnets
    log_info "Step 6/9: Deleting subnets..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        local subnets=$(aws ec2 describe-subnets \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Subnets[].SubnetId" --output text)

        for subnet in $subnets; do
            if [[ "$DRY_RUN" == "false" ]]; then
                aws ec2 delete-subnet --subnet-id "$subnet" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
                log_info "  Deleted Subnet: $subnet"
            else
                log_info "  [DRY RUN] Would delete Subnet: $subnet"
            fi
        done
    fi

    # 7. Delete Internet Gateway and Route Tables
    log_info "Step 7/9: Deleting internet gateway and route tables..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        # Internet Gateway
        local igw=$(aws ec2 describe-internet-gateways \
            --filters "Name=attachment.vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "InternetGateways[0].InternetGatewayId" --output text)

        if [[ "$igw" != "None" && -n "$igw" ]]; then
            if [[ "$DRY_RUN" == "false" ]]; then
                aws ec2 detach-internet-gateway --internet-gateway-id "$igw" --vpc-id "$vpc_id" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
                aws ec2 delete-internet-gateway --internet-gateway-id "$igw" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
                log_info "  Deleted Internet Gateway: $igw"
            else
                log_info "  [DRY RUN] Would delete Internet Gateway: $igw"
            fi
        fi

        # Route Tables
        local rts=$(aws ec2 describe-route-tables \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "RouteTables[?Associations[0].Main!=\`true\`].RouteTableId" --output text)

        for rt in $rts; do
            if [[ "$DRY_RUN" == "false" ]]; then
                aws ec2 delete-route-table --route-table-id "$rt" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
                log_info "  Deleted Route Table: $rt"
            else
                log_info "  [DRY RUN] Would delete Route Table: $rt"
            fi
        done
    fi

    # 8. Delete VPC
    log_info "Step 8/9: Deleting VPC..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        if [[ "$DRY_RUN" == "false" ]]; then
            aws ec2 delete-vpc --vpc-id "$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null || true
            log_info "  Deleted VPC: $vpc_id"
        else
            log_info "  [DRY RUN] Would delete VPC: $vpc_id"
        fi
    fi

    # 9. Clean up Route53 DNS records
    log_info "Step 9/9: Cleaning up Route53 DNS records..."
    cleanup_route53_records "$infra_id"

    log_success "Manual resource cleanup completed"
}

# Clean up S3 state
cleanup_s3_state() {
    local cluster_name="$1"

    log_info "Cleaning up S3 state for cluster: $cluster_name"

    if aws s3 ls "s3://${S3_BUCKET}/${cluster_name}/" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then

        if [[ "$DRY_RUN" == "false" ]]; then
            aws s3 rm "s3://${S3_BUCKET}/${cluster_name}/" --recursive \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null
            log_success "Deleted S3 state: s3://${S3_BUCKET}/${cluster_name}/"
        else
            log_info "[DRY RUN] Would delete S3 state: s3://${S3_BUCKET}/${cluster_name}/"
        fi
    else
        log_info "No S3 state found for cluster: $cluster_name"
    fi
}

# Main execution
main() {
    log_info "OpenShift Cluster Destroyer started at $(date)"
    log_info "Log file: $LOG_FILE"

    # Parse and validate inputs
    parse_args "$@"
    validate_inputs

    # Extract metadata if file provided
    if [[ -n "$METADATA_FILE" ]]; then
        if ! extract_metadata "$METADATA_FILE"; then
            log_error "Failed to extract metadata from: $METADATA_FILE"
            exit 1
        fi
    fi

    # Auto-detect infrastructure ID if needed
    if [[ -z "$INFRA_ID" && -n "$CLUSTER_NAME" ]]; then
        if ! detect_infra_id "$CLUSTER_NAME"; then
            log_error "Could not find infrastructure ID for cluster: $CLUSTER_NAME"
            log_info "The cluster might not exist or might already be deleted"
            exit 1
        fi
    fi

    # Ensure we have an infrastructure ID at this point
    if [[ -z "$INFRA_ID" ]]; then
        log_error "No infrastructure ID found or provided"
        exit 1
    fi

    # Count resources
    echo ""
    log_info "${BOLD}Cluster Destruction Summary${NC}"
    log_info "Cluster Name:     ${CLUSTER_NAME:-unknown}"
    log_info "Infrastructure ID: $INFRA_ID"
    log_info "AWS Region:       $AWS_REGION"
    log_info "AWS Profile:      $AWS_PROFILE"
    log_info "Mode:             $([ "$DRY_RUN" == "true" ] && echo "DRY RUN" || echo "LIVE")"
    echo ""

    local resource_count=$(count_resources "$INFRA_ID")
    log_info "Total AWS resources found: $resource_count"

    if [[ "$resource_count" -eq 0 ]]; then
        log_warning "No AWS resources found for this cluster"
        cleanup_s3_state "${CLUSTER_NAME:-$INFRA_ID}"
        log_success "Cluster cleanup completed (no resources to delete)"
        exit 0
    fi

    # Show detailed resource list for both dry-run and normal mode
    # In normal mode, also show confirmation prompt (unless --force is used)
    if [[ "$resource_count" -gt 0 ]]; then
        echo ""
        log_info "${BOLD}$([ "$DRY_RUN" == "true" ] && echo "RESOURCES THAT WOULD BE DELETED:" || echo "RESOURCES TO BE DELETED:")${NC}"
        echo ""

        # List EC2 Instances
        local instances=$(aws ec2 describe-instances \
            --filters "Name=tag:kubernetes.io/cluster/$INFRA_ID,Values=owned" \
                      "Name=instance-state-name,Values=running,stopped,stopping,pending" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Reservations[].Instances[].[InstanceId,InstanceType,Tags[?Key=='Name'].Value|[0]]" \
            --output text 2>/dev/null)

        if [[ -n "$instances" ]]; then
            log_info "EC2 Instances:"
            echo "$instances" | while read id type name; do
                echo "  - $id ($type) - $name"
            done
        fi

        # List Load Balancers
        local nlbs=$(aws elbv2 describe-load-balancers \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "LoadBalancers[?contains(LoadBalancerName, '$INFRA_ID')].[LoadBalancerName,Type]" \
            --output text 2>/dev/null)

        if [[ -n "$nlbs" ]]; then
            log_info "Load Balancers:"
            echo "$nlbs" | while read name type; do
                echo "  - $name ($type)"
            done
        fi

        # List NAT Gateways
        local nats=$(aws ec2 describe-nat-gateways \
            --filter "Name=tag:kubernetes.io/cluster/$INFRA_ID,Values=owned" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "NatGateways[?State!='deleted'].[NatGatewayId,State]" \
            --output text 2>/dev/null)

        if [[ -n "$nats" ]]; then
            log_info "NAT Gateways:"
            echo "$nats" | while read id state; do
                echo "  - $id ($state)"
            done
        fi

        # List Elastic IPs
        local eips=$(aws ec2 describe-addresses \
            --filters "Name=tag:kubernetes.io/cluster/$INFRA_ID,Values=owned" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Addresses[].[AllocationId,PublicIp]" \
            --output text 2>/dev/null)

        if [[ -n "$eips" ]]; then
            log_info "Elastic IPs:"
            echo "$eips" | while read id ip; do
                echo "  - $id ($ip)"
            done
        fi

        # List VPC
        local vpc=$(aws ec2 describe-vpcs \
            --filters "Name=tag:kubernetes.io/cluster/$INFRA_ID,Values=owned" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Vpcs[0].[VpcId,CidrBlock]" \
            --output text 2>/dev/null)

        if [[ -n "$vpc" && "$vpc" != "None" ]]; then
            log_info "VPC:"
            echo "  - $(echo $vpc | awk '{print $1}') ($(echo $vpc | awk '{print $2}'))"

            # Count subnets
            local subnet_count=$(aws ec2 describe-subnets \
                --filters "Name=vpc-id,Values=$(echo $vpc | awk '{print $1}')" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "Subnets | length(@)" --output text 2>/dev/null)
            echo "    - $subnet_count subnets"

            # Count security groups
            local sg_count=$(aws ec2 describe-security-groups \
                --filters "Name=vpc-id,Values=$(echo $vpc | awk '{print $1}')" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "SecurityGroups | length(@)" --output text 2>/dev/null)
            echo "    - $sg_count security groups"

            # Count route tables
            local rt_count=$(aws ec2 describe-route-tables \
                --filters "Name=vpc-id,Values=$(echo $vpc | awk '{print $1}')" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "RouteTables | length(@)" --output text 2>/dev/null)
            echo "    - $rt_count route tables"
        fi

        # Check Route53 records
        local cluster_name="${CLUSTER_NAME:-${INFRA_ID%-*}}"
        local zone_id=$(aws route53 list-hosted-zones \
            --query "HostedZones[?Name=='${BASE_DOMAIN}.'].Id" \
            --output text --profile "$AWS_PROFILE" 2>/dev/null | head -1)

        if [[ -n "$zone_id" ]]; then
            local dns_count=0

            # Check for api record
            if aws route53 list-resource-record-sets \
                --hosted-zone-id "$zone_id" \
                --query "ResourceRecordSets[?Name=='api.${cluster_name}.${BASE_DOMAIN}.']" \
                --profile "$AWS_PROFILE" 2>/dev/null | grep -q "api.${cluster_name}"; then
                ((dns_count++))
            fi

            # Check for apps record
            if aws route53 list-resource-record-sets \
                --hosted-zone-id "$zone_id" \
                --query "ResourceRecordSets[?Name=='\\052.apps.${cluster_name}.${BASE_DOMAIN}.']" \
                --profile "$AWS_PROFILE" 2>/dev/null | grep -q "apps.${cluster_name}"; then
                ((dns_count++))
            fi

            if [[ $dns_count -gt 0 ]]; then
                log_info "Route53 DNS Records:"
                echo "  - api.${cluster_name}.${BASE_DOMAIN}"
                echo "  - *.apps.${cluster_name}.${BASE_DOMAIN}"
            fi
        fi

        # Check S3 state
        if [[ -n "$CLUSTER_NAME" ]]; then
            if aws s3 ls "s3://${S3_BUCKET}/${CLUSTER_NAME}/" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then
                log_info "S3 State:"
                echo "  - s3://${S3_BUCKET}/${CLUSTER_NAME}/"
            fi
        fi

        echo ""

        # Add summary
        local total_resources=0
        [[ -n "$instances" ]] && total_resources=$((total_resources + $(echo "$instances" | wc -l)))
        [[ -n "$nlbs" ]] && total_resources=$((total_resources + $(echo "$nlbs" | wc -l)))
        [[ -n "$nats" ]] && total_resources=$((total_resources + $(echo "$nats" | wc -l)))
        [[ -n "$eips" ]] && total_resources=$((total_resources + $(echo "$eips" | wc -l)))
        [[ -n "$vpc" && "$vpc" != "None" ]] && total_resources=$((total_resources + 1 + subnet_count + sg_count + rt_count))

        log_info "${BOLD}TOTAL: Approximately $total_resources AWS resources $([ "$DRY_RUN" == "true" ] && echo "would be" || echo "will be") deleted${NC}"

        # Show confirmation only in normal mode (not dry-run)
        if [[ "$DRY_RUN" != "true" ]]; then
            if [[ "$FORCE" != "true" ]]; then
                echo ""
                log_warning "[!] THIS ACTION CANNOT BE UNDONE!"
                echo ""
                read -p "Are you sure you want to destroy ALL the above resources? Type 'yes' to continue: " -r confirm
                if [[ "$confirm" != "yes" ]]; then
                    log_warning "Destruction cancelled by user"
                    exit 0
                fi
            fi
        fi
    fi

    # Priority order for destruction methods:
    # 1. Try openshift-install with S3 state (if available)
    # 2. Fall back to manual AWS cleanup

    local use_openshift_install=false

    # Try openshift-install if we have cluster name and S3 state
    if [[ -n "$CLUSTER_NAME" ]]; then
        log_info "Checking for S3 state to use openshift-install..."

        # Check if S3 has cluster state
        if aws s3 ls "s3://${S3_BUCKET}/${CLUSTER_NAME}/metadata.json" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then

            log_info "Found cluster state in S3, downloading for openshift-install..."

            local temp_dir="/tmp/openshift-destroy-${CLUSTER_NAME}-$$"
            mkdir -p "$temp_dir"

            # Download all cluster state from S3
            if aws s3 sync "s3://${S3_BUCKET}/${CLUSTER_NAME}/" "$temp_dir/" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" --quiet; then

                if [[ -f "$temp_dir/metadata.json" ]]; then
                    log_info "Successfully downloaded cluster state, using openshift-install..."

                    # Extract infrastructure ID from metadata if not already set
                    if [[ -z "$INFRA_ID" ]]; then
                        INFRA_ID=$(jq -r '.infraID // empty' "$temp_dir/metadata.json" 2>/dev/null)
                        if [[ -n "$INFRA_ID" ]]; then
                            log_info "Extracted infrastructure ID: $INFRA_ID"
                        fi
                    fi

                    # Try openshift-install destroy
                    if destroy_with_openshift_install "$temp_dir"; then
                        use_openshift_install=true
                        log_success "OpenShift installer completed successfully"
                    else
                        log_warning "OpenShift installer failed or incomplete, will run manual cleanup"
                    fi
                else
                    log_warning "No metadata.json found in S3 state"
                fi
            else
                log_warning "Failed to download cluster state from S3"
            fi

            rm -rf "$temp_dir"
        else
            log_info "No S3 state found for cluster: $CLUSTER_NAME"
        fi
    fi

    # Always run manual AWS cleanup to ensure all resources are deleted
    # This catches any resources that openshift-install might have missed
    log_info "Running comprehensive AWS resource cleanup..."
    destroy_aws_resources "$INFRA_ID"

    # Clean up S3 state
    if [[ -n "$CLUSTER_NAME" ]]; then
        cleanup_s3_state "$CLUSTER_NAME"
    fi

    # Final verification
    echo ""
    log_info "${BOLD}Post-destruction verification...${NC}"
    local remaining=$(count_resources "$INFRA_ID")
    if [[ "$remaining" -eq 0 ]]; then
        log_success "All cluster resources successfully removed!"
    else
        log_warning "$remaining resources may still exist. Check AWS console."
    fi

    log_info "Destruction completed at $(date)"
    log_info "Full log available at: $LOG_FILE"
}

# Run main function
main "$@"

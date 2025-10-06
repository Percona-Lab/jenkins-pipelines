#!/bin/bash
#
# OpenShift Cluster Destroyer Script
#
# Safely destroys OpenShift clusters on AWS by cleaning up all associated resources.
# Handles various cluster states including properly installed, orphaned, and failed installations.
#
# CAPABILITIES:
#   • Auto-detects infrastructure IDs from cluster names
#   • Downloads and uses cluster state from S3 for openshift-install destroy
#   • Comprehensive resource cleanup including EC2, VPC, Route53, ELB, S3, and EBS
#   • Reconciliation loop ensures thorough cleanup of stubborn resources
#   • Dry-run mode for safety verification before deletion
#   • In-memory caching to optimize repeated AWS API calls
#   • Tested with OpenShift versions 4.16 through 4.19
#
# USAGE: 
#   ./destroy-openshift-cluster.sh [OPTIONS]
#
# COMMANDS:
#   --list                 List all OpenShift clusters in the region with details
#
# DESTRUCTION OPTIONS (choose one):
#   --cluster-name NAME    Base cluster name (auto-detects infrastructure ID)
#   --infra-id ID         Direct infrastructure ID (e.g., cluster-name-xxxxx)
#   --metadata-file PATH   Path to OpenShift metadata.json file
#
# AWS CONFIGURATION:
#   --region REGION       AWS region (default: us-east-2)
#   --profile PROFILE     AWS CLI profile (default: percona-dev-admin)
#   --s3-bucket BUCKET    S3 bucket for cluster state (auto-detected if not set)
#
# SAFETY & BEHAVIOR:
#   --dry-run            Preview resources to be deleted without making changes
#   --force              Skip confirmation prompts (use with caution)
#   --verbose            Enable detailed debug output
#   --max-attempts NUM   Maximum reconciliation attempts (default: 5)
#
# ROUTE53 OPTIONS:
#   --base-domain DOMAIN  Base domain for Route53 cleanup (default: cd.percona.com)
#
# HELP:
#   --help               Display this help message

set -euo pipefail
unset PAGER

# Check for required dependencies
check_dependencies() {
    local missing_deps=()
    
    # Check for required commands
    for cmd in aws; do
        if ! command -v "$cmd" &>/dev/null; then
            missing_deps+=("$cmd")
        fi
    done
    
    # Check for optional but recommended commands
    if ! command -v timeout &>/dev/null; then
        echo "WARNING: 'timeout' command not found. Operations may hang indefinitely." >&2
        echo "         Install coreutils (macOS: brew install coreutils, Linux: usually pre-installed)" >&2
    fi
    
    if [[ ${#missing_deps[@]} -gt 0 ]]; then
        echo "ERROR: Required dependencies are missing:" >&2
        for dep in "${missing_deps[@]}"; do
            echo "  - $dep" >&2
        done
        echo "" >&2
        echo "Please install missing dependencies:" >&2
        echo "  macOS: brew install awscli" >&2
        echo "  Linux: apt-get install awscli  # or yum/dnf equivalent" >&2
        exit 1
    fi
}

# Check dependencies before proceeding
check_dependencies

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
MAX_ATTEMPTS=5
LOG_FILE=""  # Custom log file path (optional)
LOGGING_ENABLED=false  # Logging disabled by default
COLOR_ENABLED=true  # Enable/disable colored output

# CloudWatch configuration
CLOUDWATCH_LOG_GROUP="/aws/openshift/cluster-destroyer"
CLOUDWATCH_LOG_STREAM=""
CLOUDWATCH_ENABLED=false
CLOUDWATCH_SEQUENCE_TOKEN=""
# CloudWatch region will be set to match AWS_REGION

# Check if CloudWatch logging is available
check_cloudwatch_access() {
    # Check if AWS CLI is configured and we can access CloudWatch
    if aws logs describe-log-groups --log-group-name-prefix "$CLOUDWATCH_LOG_GROUP" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then
        return 0
    fi
    return 1
}

# Initialize CloudWatch logging
setup_cloudwatch_logging() {
    # Only setup if AWS is properly configured
    if ! check_cloudwatch_access; then
        return 1
    fi
    
    # Create log group if it doesn't exist
    # Create log group if it doesn't exist (ignore AlreadyExists error)
    aws logs create-log-group --log-group-name "$CLOUDWATCH_LOG_GROUP" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>&1 | grep -v "ResourceAlreadyExistsException" || true
    
    # Create unique log stream name
    local timestamp=$(date +%Y%m%d-%H%M%S)
    local user=$(aws sts get-caller-identity --profile "$AWS_PROFILE" --region "$AWS_REGION" \
        --query 'UserId' --output text 2>/dev/null | cut -d: -f2)
    CLOUDWATCH_LOG_STREAM="${user:-unknown}-${timestamp}-$$"
    
    # Create log stream
    if aws logs create-log-stream \
        --log-group-name "$CLOUDWATCH_LOG_GROUP" \
        --log-stream-name "$CLOUDWATCH_LOG_STREAM" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null; then
        CLOUDWATCH_ENABLED=true
        # Don't echo here, let main() handle it after setup_logging completes
        return 0
    fi
    
    return 1
}

# Send log message to CloudWatch
send_to_cloudwatch() {
    local message="$1"
    
    [[ "$CLOUDWATCH_ENABLED" != "true" ]] && return 0
    
    # Prepare log event
    local timestamp=$(date +%s000)  # Milliseconds since epoch
    local escaped_msg=$(echo "$message" | sed 's/"/\\"/g')
    local log_event='[{"message":"'"$escaped_msg"'","timestamp":'"$timestamp"'}]'
    
    # Send to CloudWatch (fire and forget to avoid slowing down the script)
    {
        if [[ -n "$CLOUDWATCH_SEQUENCE_TOKEN" ]]; then
            result=$(aws logs put-log-events \
                --log-group-name "$CLOUDWATCH_LOG_GROUP" \
                --log-stream-name "$CLOUDWATCH_LOG_STREAM" \
                --log-events "$log_event" \
                --sequence-token "$CLOUDWATCH_SEQUENCE_TOKEN" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null)
        else
            result=$(aws logs put-log-events \
                --log-group-name "$CLOUDWATCH_LOG_GROUP" \
                --log-stream-name "$CLOUDWATCH_LOG_STREAM" \
                --log-events "$log_event" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null)
        fi
        
        # Update sequence token for next call
        if [[ -n "$result" ]]; then
            # Extract sequence token using grep and cut
            CLOUDWATCH_SEQUENCE_TOKEN=$(echo "$result" | grep -o '"nextSequenceToken":"[^"]*"' | cut -d'"' -f4)
        fi
    } 2>/dev/null &
}

# Set up log directory and file
setup_logging() {
    # Skip logging setup if disabled
    if [[ "$LOGGING_ENABLED" == "false" ]]; then
        LOG_FILE="/dev/null"
        return 0
    fi
    
    # Use custom log file if specified
    if [[ -n "$LOG_FILE" ]]; then
        # Ensure the directory exists
        local custom_dir=$(dirname "$LOG_FILE")
        if ! mkdir -p "$custom_dir" 2>/dev/null; then
            echo "ERROR: Cannot create log directory: $custom_dir" >&2
            exit 1
        fi
        # Touch and set permissions
        touch "$LOG_FILE" 2>/dev/null || {
            echo "ERROR: Cannot create log file: $LOG_FILE" >&2
            exit 1
        }
        chmod 600 "$LOG_FILE" 2>/dev/null || true
        # Don't echo here, let main() handle it after setup_logging completes
        # Try to set up CloudWatch logging
        setup_cloudwatch_logging || true
        return 0
    fi
    
    local log_dir=""

    # Use different locations based on environment
    if [[ -n "${WORKSPACE:-}" ]] && [[ -d "${WORKSPACE}" ]]; then
        # Jenkins/CI environment - logs go to workspace
        log_dir="${WORKSPACE}/logs"
    elif [[ -n "${CI_PROJECT_DIR:-}" ]] && [[ -d "${CI_PROJECT_DIR}" ]]; then
        # GitLab CI environment
        log_dir="${CI_PROJECT_DIR}/logs"
    else
        # Local execution - use /var/log for system-wide logging
        log_dir="/var/log/openshift-destroy"

        # Ensure the directory exists
        if [[ ! -d "$log_dir" ]]; then
            sudo mkdir -p "$log_dir" 2>/dev/null || {
                echo "ERROR: Cannot create log directory: $log_dir" >&2
                echo "       Run with sudo or ensure /var/log is writable" >&2
                exit 1
            }
        fi

        # Ensure directory is writable
        if [[ ! -w "$log_dir" ]]; then
            sudo chmod 755 "$log_dir" 2>/dev/null
            sudo chown "$USER" "$log_dir" 2>/dev/null || {
                echo "ERROR: Cannot write to log directory: $log_dir" >&2
                echo "       Run with sudo or ensure proper permissions" >&2
                exit 1
            }
        fi
    fi

    # Create log directory
    mkdir -p "$log_dir" 2>/dev/null || {
        echo "ERROR: Failed to create log directory: $log_dir" >&2
        exit 1
    }
    
    LOG_FILE="${log_dir}/destroy-$(date +%Y%m%d-%H%M%S)-$$.log"
    
    # Ensure log file is created with restricted permissions
    touch "$LOG_FILE"
    chmod 600 "$LOG_FILE"
    
    # Don't echo here, let main() handle it after setup_logging completes
    
    # Try to set up CloudWatch logging
    setup_cloudwatch_logging || true
}

# Setup color codes based on COLOR_ENABLED setting
setup_colors() {
    if [[ "$COLOR_ENABLED" == "true" ]]; then
        RED='\033[0;31m'
        GREEN='\033[0;32m'
        YELLOW='\033[1;33m'
        BLUE='\033[0;34m'
        CYAN='\033[0;36m'
        BOLD='\033[1m'
        NC='\033[0m' # No Color
    else
        # Disable all colors
        RED=''
        GREEN=''
        YELLOW=''
        BLUE=''
        CYAN=''
        BOLD=''
        NC=''
    fi
}

# Initialize colors with defaults (will be updated after parsing args)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Logging functions
log() {
    local message="${1}"
    if [[ "$LOGGING_ENABLED" == "true" ]] && [[ -n "$LOG_FILE" ]]; then
        echo -e "${message}" | tee -a "$LOG_FILE"
        # Also send to CloudWatch if enabled
        send_to_cloudwatch "$(echo -e "${message}" | sed 's/\x1b\[[0-9;]*m//g')" # Strip color codes for CloudWatch
    else
        echo -e "${message}"
    fi
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

# Execute command with timeout
# Usage: execute_with_timeout <timeout_seconds> <command> [args...]
execute_with_timeout() {
    local timeout_sec="$1"
    shift

    log_debug "Executing with ${timeout_sec}s timeout: $*"

    # Use timeout command if available
    if command -v timeout &>/dev/null; then
        if timeout "$timeout_sec" "$@" 2>&1; then
            return 0
        else
            local exit_code=$?
            if [[ $exit_code -eq 124 ]]; then
                log_warning "Command timed out after ${timeout_sec}s, continuing..."
                return 124
            else
                return $exit_code
            fi
        fi
    else
        # Fallback: run without timeout if timeout command not available
        log_warning "timeout command not available, running without timeout"
        "$@"
    fi
}

# Wait for network interfaces to detach from VPC
# Polls AWS API until all in-use ENIs (that won't auto-delete) are detached
wait_for_network_interfaces() {
    local vpc_id="$1"
    local max_wait=300  # 5 minutes
    local elapsed=0
    local check_interval=10

    log_info "Waiting for network interfaces to detach from VPC: $vpc_id"

    while [[ $elapsed -lt $max_wait ]]; do
        local eni_count
        eni_count=$(aws ec2 describe-network-interfaces \
            --filters "Name=vpc-id,Values=$vpc_id" \
            "Name=status,Values=in-use" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "NetworkInterfaces[?Attachment.DeleteOnTermination==\`false\`] | length(@)" \
            --output text 2>/dev/null || echo "0")

        if [[ "$eni_count" -eq 0 ]]; then
            log_success "All network interfaces detached"
            return 0
        fi

        log_debug "Waiting for $eni_count network interface(s) to detach... (${elapsed}s/${max_wait}s)"
        sleep $check_interval
        elapsed=$((elapsed + check_interval))
    done

    log_warning "Timeout waiting for network interfaces after ${max_wait}s"
    return 1
}

# Help function
show_help() {
    cat <<EOF
${BOLD}OpenShift Cluster Destroyer Script${NC}

Safely destroys OpenShift clusters on AWS by cleaning up all associated resources.
Handles various cluster states including properly installed, orphaned, and failed installations.

${BOLD}CAPABILITIES:${NC}
  • Auto-detects infrastructure IDs from cluster names
  • Downloads and uses cluster state from S3 for openshift-install destroy
  • Comprehensive resource cleanup including EC2, VPC, Route53, ELB, S3, and EBS
  • Reconciliation loop ensures thorough cleanup of stubborn resources
  • Dry-run mode for safety verification before deletion
  • In-memory caching to optimize repeated AWS API calls

${BOLD}USAGE:${NC}
  $(basename "$0") [OPTIONS]

${BOLD}COMMANDS:${NC}
  --list                 List all OpenShift clusters in the region with details

${BOLD}DESTRUCTION OPTIONS${NC} (choose one):
  --cluster-name NAME    Base cluster name (auto-detects infrastructure ID)
  --infra-id ID         Direct infrastructure ID (e.g., cluster-name-xxxxx)
  --metadata-file PATH   Path to OpenShift metadata.json file

${BOLD}AWS CONFIGURATION:${NC}
  --region REGION       AWS region (default: us-east-2)
  --profile PROFILE     AWS CLI profile (default: percona-dev-admin)
  --s3-bucket BUCKET    S3 bucket for cluster state (auto-detected if not set)

${BOLD}SAFETY & BEHAVIOR:${NC}
  --dry-run            Preview resources to be deleted without making changes
  --force              Skip confirmation prompts (use with caution)
  --verbose            Enable detailed debug output
  --max-attempts NUM   Maximum reconciliation attempts (default: 5)

${BOLD}ROUTE53 OPTIONS:${NC}
  --base-domain DOMAIN  Base domain for Route53 cleanup (default: cd.percona.com)

${BOLD}LOGGING:${NC}
  --log                Enable logging to auto-determined file location
  --log-file PATH      Enable logging to specific file path
  --no-color           Disable colored output

${BOLD}HELP:${NC}
  --help               Display this help message

${BOLD}EXAMPLES:${NC}
  # Destroy using cluster name (auto-detects infrastructure ID)
  $(basename "$0") --cluster-name helm-test

  # Preview what would be deleted (dry-run)
  $(basename "$0") --cluster-name helm-test --dry-run

  # Force destruction without confirmation
  $(basename "$0") --cluster-name helm-test --force

  # Destroy using specific infrastructure ID
  $(basename "$0") --infra-id helm-test-tqtlx

  # List all clusters in us-west-2
  $(basename "$0") --list --region us-west-2

  # Destroy with custom settings and logging
  $(basename "$0") --cluster-name test-cluster \\
    --region us-west-2 \\
    --s3-bucket my-bucket \\
    --log-file ./destroy.log \\
    --verbose

${BOLD}DESTRUCTION PROCESS:${NC}
  1. Attempts to download cluster state from S3
  2. Extracts cluster-state.tar.gz if present
  3. Uses openshift-install destroy if metadata.json found
  4. Performs comprehensive manual cleanup for remaining resources
  5. Runs reconciliation loop to ensure all resources are deleted
  6. Cleans up Route53 records and S3 state

${BOLD}NOTES:${NC}
  • Tested with OpenShift versions 4.16 through 4.19
  • The script prioritizes openshift-install when cluster state exists
  • Manual cleanup runs if openshift-install fails or state is missing
  • Reconciliation ensures eventual consistency for resource deletion
  • Cached API calls significantly improve performance on large cleanups
  • Default log locations (when --log is used):
    - CI environment: $WORKSPACE/logs or $CI_PROJECT_DIR/logs
    - Local execution: /var/log/openshift-destroy/ (requires sudo if not writable)
  • Log filename format: destroy-YYYYMMDD-HHMMSS-PID.log

EOF
    exit 0
}

# Auto-detect S3 bucket
auto_detect_s3_bucket() {
    if [[ -z "$S3_BUCKET" ]]; then
        local account_id=$(aws sts get-caller-identity \
            --profile "$AWS_PROFILE" \
            --query 'Account' --output text 2>/dev/null)

        if [[ -n "$account_id" ]]; then
            S3_BUCKET="openshift-clusters-${account_id}-${AWS_REGION}"
            log_debug "Auto-detected S3 bucket: $S3_BUCKET"
        fi
    fi
}

# Parse command line arguments
parse_args() {
    local list_mode=false

    while [[ $# -gt 0 ]]; do
        case $1 in
        --list)
            list_mode=true
            shift
            ;;
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
        --max-attempts)
            MAX_ATTEMPTS="$2"
            shift 2
            ;;
        --log)
            LOGGING_ENABLED=true
            shift
            ;;
        --log-file)
            LOGGING_ENABLED=true
            LOG_FILE="$2"
            shift 2
            ;;
        --no-color)
            COLOR_ENABLED=false
            shift
            ;;
        --help | -h)
            show_help
            ;;
        *)
            log_error "Unknown option: $1"
            show_help
            ;;
        esac
    done

    # If list mode, handle it separately
    if [[ "$list_mode" == "true" ]]; then
        # Setup colors based on user preference
        setup_colors
        
        # Initialize logging for list mode
        setup_logging
        
        log_info ""
        log_info "${BOLD}Listing OpenShift Clusters${NC}"
        log_info "Started: $(date)"
        if [[ "$LOGGING_ENABLED" == "true" ]]; then
            log_info "Log file: $LOG_FILE"
        fi
        
        # Auto-detect S3 bucket if not provided
        if [[ -z "$S3_BUCKET" ]]; then
            auto_detect_s3_bucket
        fi
        list_clusters
        exit 0
    fi
}

# Validate inputs
# Validate input to prevent injection attacks
validate_input_string() {
    local input="$1"
    local input_name="$2"
    
    # Check for empty input
    if [[ -z "$input" ]]; then
        return 0
    fi
    
    # Validate against safe pattern (alphanumeric, dash, underscore only)
    if [[ ! "$input" =~ ^[a-zA-Z0-9_-]+$ ]]; then
        log_error "$input_name contains invalid characters. Only alphanumeric, dash, and underscore allowed."
        log_error "Provided value: '$input'"
        exit 1
    fi
    
    # Check length (reasonable limits)
    if [[ ${#input} -gt 63 ]]; then
        log_error "$input_name is too long (max 63 characters)"
        exit 1
    fi
}

validate_inputs() {
    # Check if at least one identifier is provided
    if [[ -z "$CLUSTER_NAME" && -z "$INFRA_ID" && -z "$METADATA_FILE" ]]; then
        log_error "You must provide either --cluster-name, --infra-id, or --metadata-file"
        show_help
    fi
    
    # Validate input strings to prevent injection
    validate_input_string "$CLUSTER_NAME" "Cluster name"
    validate_input_string "$INFRA_ID" "Infrastructure ID"
    validate_input_string "$AWS_PROFILE" "AWS profile"
    
    # Validate AWS region format
    if [[ ! "$AWS_REGION" =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]]; then
        log_error "Invalid AWS region format: $AWS_REGION"
        exit 1
    fi
    
    # Validate base domain format
    if [[ ! "$BASE_DOMAIN" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]+[a-zA-Z0-9]$ ]]; then
        log_error "Invalid base domain format: $BASE_DOMAIN"
        exit 1
    fi
    
    # Validate max_attempts
    if [[ ! "$MAX_ATTEMPTS" =~ ^[0-9]+$ ]] || [[ "$MAX_ATTEMPTS" -lt 1 ]] || [[ "$MAX_ATTEMPTS" -gt 20 ]]; then
        log_error "Invalid max-attempts value: $MAX_ATTEMPTS (must be between 1 and 20)"
        exit 1
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
    else
        # Validate S3 bucket name if provided
        if [[ ! "$S3_BUCKET" =~ ^[a-z0-9][a-z0-9.-]*[a-z0-9]$ ]] || [[ ${#S3_BUCKET} -gt 63 ]]; then
            log_error "Invalid S3 bucket name: $S3_BUCKET"
            exit 1
        fi
    fi
}

# Extract metadata from file
extract_metadata() {
    local metadata_file="$1"

    if [[ -f "$metadata_file" ]]; then
        # Extract values using grep, sed, and awk
        INFRA_ID=$(grep -o '"infraID"[[:space:]]*:[[:space:]]*"[^"]*"' "$metadata_file" 2>/dev/null | sed 's/.*:.*"\([^"]*\)".*/\1/' || echo "")
        local extracted_cluster=$(grep -o '"clusterName"[[:space:]]*:[[:space:]]*"[^"]*"' "$metadata_file" 2>/dev/null | sed 's/.*:.*"\([^"]*\)".*/\1/' || echo "")
        # Try aws.region first using awk
        local extracted_region=$(awk '/"aws"[[:space:]]*:/{p=1} p && /"region"[[:space:]]*:/{gsub(/.*"region"[[:space:]]*:[[:space:]]*"/,""); gsub(/".*/,""); print; exit}' "$metadata_file" 2>/dev/null || echo "")
        # Fall back to platform.aws.region if needed
        if [[ -z "$extracted_region" ]]; then
            extracted_region=$(sed -n '/"platform"[[:space:]]*:/,/^[[:space:]]*}/p' "$metadata_file" 2>/dev/null | sed -n '/"aws"[[:space:]]*:/,/^[[:space:]]*}/p' | grep '"region"' | sed 's/.*"region"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/' || echo "")
        fi
        
        # Only update CLUSTER_NAME if we got a valid value
        if [[ -n "$extracted_cluster" && "$extracted_cluster" != "null" ]]; then
            CLUSTER_NAME="$extracted_cluster"
        fi
        
        # Only update AWS_REGION if we got a valid value
        if [[ -n "$extracted_region" && "$extracted_region" != "null" ]]; then
            AWS_REGION="$extracted_region"
        fi
        
        # Clean up any "null" strings that might have leaked through
        [[ "$INFRA_ID" == "null" ]] && INFRA_ID=""

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

    log_info "Searching for infrastructure ID for cluster: ${CYAN}${BOLD}$cluster_name${NC}"

    # Search for VPCs with cluster tags
    local vpc_tags=$(aws ec2 describe-vpcs \
        --filters "Name=tag-key,Values=kubernetes.io/cluster/${cluster_name}*" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[].Tags[?contains(Key, 'kubernetes.io/cluster/')].Key" \
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

        local temp_metadata="$(mktemp -t "${cluster_name}-metadata.XXXXXX.json")"
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

# Count AWS resources for a cluster (optimized with parallel execution)
count_resources() {
    local infra_id="$1"
    local resource_count=0
    local temp_dir=$(mktemp -d -t "openshift-count.XXXXXX")
    
    # Log to stderr so it doesn't interfere with return value
    log_info "Counting resources for infrastructure ID: $infra_id" >&2
    log_debug "Using parallel execution for resource counting" >&2
    
    # First, get VPC ID as we need it for several queries
    local vpc_id=$(aws ec2 describe-vpcs \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[0].VpcId" --output text 2>/dev/null)
    
    # Launch all API calls in parallel as background jobs
    
    # EC2 Instances
    (
        count=$(aws ec2 describe-instances \
            --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
            "Name=instance-state-name,Values=running,stopped,stopping,pending" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Reservations[].Instances[].InstanceId" --output text 2>/dev/null | wc -w)
        echo "$count" > "$temp_dir/instances"
        [[ $count -gt 0 ]] && echo "EC2 Instances:$count" > "$temp_dir/instances.log"
    ) &
    
    # Classic Load Balancers
    (
        count=$(aws elb describe-load-balancers \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "LoadBalancerDescriptions[?contains(LoadBalancerName, '$infra_id')].LoadBalancerName" \
            --output text 2>/dev/null | wc -w)
        echo "$count" > "$temp_dir/elbs"
        [[ $count -gt 0 ]] && echo "Classic Load Balancers:$count" > "$temp_dir/elbs.log"
    ) &
    
    # ALB/NLB Load Balancers
    (
        count=$(aws elbv2 describe-load-balancers \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "LoadBalancers[?contains(LoadBalancerName, '$infra_id')].LoadBalancerArn" \
            --output text 2>/dev/null | wc -w)
        echo "$count" > "$temp_dir/nlbs"
        [[ $count -gt 0 ]] && echo "Network/Application Load Balancers:$count" > "$temp_dir/nlbs.log"
    ) &
    
    # NAT Gateways
    (
        count=$(aws ec2 describe-nat-gateways \
            --filter "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "NatGateways[?State!='deleted'].NatGatewayId" --output text 2>/dev/null | wc -w)
        echo "$count" > "$temp_dir/nats"
        [[ $count -gt 0 ]] && echo "NAT Gateways:$count" > "$temp_dir/nats.log"
    ) &
    
    # Elastic IPs
    (
        count=$(aws ec2 describe-addresses \
            --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Addresses[].AllocationId" --output text 2>/dev/null | wc -w)
        echo "$count" > "$temp_dir/eips"
        [[ $count -gt 0 ]] && echo "Elastic IPs:$count" > "$temp_dir/eips.log"
    ) &
    
    # VPC-related resources (if VPC exists)
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        # VPC itself
        echo "1" > "$temp_dir/vpc"
        echo "VPCs:1" > "$temp_dir/vpc.log"
        
        # Subnets
        (
            count=$(aws ec2 describe-subnets \
                --filters "Name=vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "Subnets | length(@)" --output text 2>/dev/null || echo 0)
            echo "$count" > "$temp_dir/subnets"
            [[ $count -gt 0 ]] && echo "Subnets:$count" > "$temp_dir/subnets.log"
        ) &
        
        # Security Groups (excluding default)
        (
            count=$(aws ec2 describe-security-groups \
                --filters "Name=vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "SecurityGroups[?GroupName!='default'] | length(@)" --output text 2>/dev/null || echo 0)
            echo "$count" > "$temp_dir/sgs"
            [[ $count -gt 0 ]] && echo "Security Groups:$count" > "$temp_dir/sgs.log"
        ) &
        
        # Route Tables (excluding main)
        (
            count=$(aws ec2 describe-route-tables \
                --filters "Name=vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "RouteTables[?Associations[0].Main!=\`true\`] | length(@)" --output text 2>/dev/null || echo 0)
            echo "$count" > "$temp_dir/rts"
            [[ $count -gt 0 ]] && echo "Route Tables:$count" > "$temp_dir/rts.log"
        ) &
        
        # Internet Gateways
        (
            count=$(aws ec2 describe-internet-gateways \
                --filters "Name=attachment.vpc-id,Values=$vpc_id" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "InternetGateways | length(@)" --output text 2>/dev/null || echo 0)
            echo "$count" > "$temp_dir/igws"
            [[ $count -gt 0 ]] && echo "Internet Gateways:$count" > "$temp_dir/igws.log"
        ) &
    else
        echo "0" > "$temp_dir/vpc"
    fi
    
    # Wait for all background jobs to complete
    # Use jobs -p to get list of background job PIDs and wait for each
    local job_pids=$(jobs -p)
    if [[ -n "$job_pids" ]]; then
        for pid in $job_pids; do
            wait "$pid" 2>/dev/null || true
        done
    else
        # Fallback: just wait for all
        wait 2>/dev/null || true
    fi
    
    # Process log files for output (maintain original formatting)
    for logfile in "$temp_dir"/*.log; do
        if [[ -f "$logfile" ]]; then
            while IFS=: read -r label count; do
                # Clean up extra spaces in label for consistent formatting
                label=$(echo "$label" | sed 's/  */ /g')
                log_info "$label: $count" >&2
            done < "$logfile"
        fi
    done
    
    # Sum up all counts
    for countfile in "$temp_dir"/*; do
        if [[ -f "$countfile" && ! "$countfile" =~ \.log$ ]]; then
            count=$(cat "$countfile" 2>/dev/null || echo 0)
            ((resource_count += count))
        fi
    done
    
    # Clean up temp directory
    rm -rf "$temp_dir"
    
    echo "$resource_count"
}

# Try to destroy using openshift-install
destroy_with_openshift_install() {
    local cluster_dir="$1"

    log_info "Attempting destruction with openshift-install..."

    # Check if openshift-install is available
    if ! command -v openshift-install &>/dev/null; then
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

# Clean up Route53 DNS records (improved version with better error handling)
cleanup_route53_records() {
    local infra_id="$1"
    local cluster_name="${CLUSTER_NAME:-${infra_id%-*}}"
    local base_domain="${BASE_DOMAIN:-cd.percona.com}"

    log_info "  Checking Route53 DNS records..."
    log_debug "Looking for: api.$cluster_name.$base_domain and *.apps.$cluster_name.$base_domain"

    # Get hosted zone ID with proper error handling
    local zone_id
    zone_id=$(aws route53 list-hosted-zones \
        --query "HostedZones[?Name=='${base_domain}.'].Id | [0]" \
        --output text --profile "$AWS_PROFILE" 2>/dev/null)
    
    if [[ -z "$zone_id" || "$zone_id" == "None" ]]; then
        log_debug "No hosted zone found for domain: $base_domain"
        return 0
    fi
    
    log_debug "Found hosted zone: $zone_id"
    
    # Define exact record names (Note: Route53 stores wildcard as \052)
    local api_name="api.${cluster_name}.${base_domain}."

    # Fetch all records
    local all_records
    if ! all_records=$(aws route53 list-resource-record-sets \
        --hosted-zone-id "$zone_id" \
        --profile "$AWS_PROFILE" \
        --output json 2>/dev/null); then
        log_warning "Failed to query Route53 records"
        return 1
    fi
    
    # Function to extract a complete record by name
    extract_route53_record() {
        local json="$1"
        local target_name="$2"
        
        echo "$json" | awk -v name="$target_name" '
        BEGIN { in_record=0; brace_count=0; found=0; record="" }
        /"Name"[[:space:]]*:[[:space:]]*"/ {
            gsub(/.*"Name"[[:space:]]*:[[:space:]]*"/, "")
            gsub(/".*/, "")
            if ($0 == name) {
                found=1
                in_record=1
                brace_count=1
                record="{"
                next
            }
        }
        in_record && found {
            record = record "\n" $0
            gsub(/[^{}]/, "", $0)
            for (i=1; i<=length($0); i++) {
                c = substr($0, i, 1)
                if (c == "{") brace_count++
                if (c == "}") {
                    brace_count--
                    if (brace_count == 0) {
                        print record
                        exit
                    }
                }
            }
        }
        '
    }
    
    # Extract field from record
    extract_field() {
        local record="$1"
        local field="$2"
        echo "$record" | grep "\"$field\"" | head -1 | sed "s/.*\"$field\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/"
    }
    
    # Find our specific records
    local api_record=$(extract_route53_record "$all_records" "$api_name")
    local apps_record_name="\\\\052.apps.${cluster_name}.${base_domain}."
    local apps_record=$(extract_route53_record "$all_records" "$apps_record_name")
    
    local count=0
    [[ -n "$api_record" ]] && ((count++))
    [[ -n "$apps_record" ]] && ((count++))
    
    if [[ $count -eq 0 ]]; then
        log_info "  No Route53 records found for cluster"
        return 0
    fi
    
    log_info "  Found $count Route53 DNS record(s) to clean up"
    
    # Process each record
    for record in "$api_record" "$apps_record"; do
        [[ -z "$record" ]] && continue
        
        local name=$(extract_field "$record" "Name")
        local type=$(extract_field "$record" "Type")
        
        if [[ "$DRY_RUN" == "false" ]]; then
            # Create change batch using string concatenation
            local change_batch="{\"Changes\":[{\"Action\":\"DELETE\",\"ResourceRecordSet\":$record}]}"
            
            # Apply the change with explicit error handling
            if aws route53 change-resource-record-sets \
                --hosted-zone-id "$zone_id" \
                --change-batch "$change_batch" \
                --profile "$AWS_PROFILE" \
                --output json >/dev/null 2>&1; then
                log_info "    Deleted DNS record: $name ($type)"
            else
                # Don't mask the error, but continue with other records
                log_warning "    Failed to delete DNS record: $name ($type) - may already be deleted"
            fi
        else
            log_info "    [DRY RUN] Would delete DNS record: $name ($type)"
        fi
    done
    
    return 0
}

# Single pass of AWS resource cleanup
destroy_aws_resources_single_pass() {
    local infra_id="$1"
    local attempt="$2"
    local max_attempts="$3"
    
    log_info "Resource deletion attempt $attempt/$max_attempts for: $infra_id"

    if [[ "$DRY_RUN" == "true" && "$attempt" -eq 1 ]]; then
        log_warning "DRY RUN MODE - No resources will be deleted"
    fi

    # 1. Terminate EC2 Instances
    log_info "Step 1/11: Terminating EC2 instances..."
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

    # 2. Delete Load Balancers (CRITICAL - must be done early to release public IPs)
    log_info "Step 2/11: Deleting load balancers..."
    log_debug "Load balancers must be deleted before IGW detachment due to public IP mappings"

    # Get VPC ID first for better ELB detection
    local vpc_id=$(aws ec2 describe-vpcs \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[0].VpcId" --output text)

    # Classic ELBs - check both by name pattern AND by VPC association
    local elbs=""
    
    # First, get ELBs by name pattern (with timeout to prevent hanging)
    log_debug "Checking for Classic ELBs by name pattern: $infra_id"
    if elbs=$(execute_with_timeout 30 aws elb describe-load-balancers \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "LoadBalancerDescriptions[?contains(LoadBalancerName, '$infra_id')].LoadBalancerName" \
        --output text 2>/dev/null); then
        log_debug "Found ELBs by name: ${elbs:-none}"
    else
        log_debug "Failed to query ELBs by name (timeout or error)"
        elbs=""
    fi
    
    # Also get ALL ELBs in the VPC (some may not have infra-id in name)
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        log_debug "Checking for Classic ELBs in VPC: $vpc_id"
        local vpc_elbs=""
        if vpc_elbs=$(execute_with_timeout 30 aws elb describe-load-balancers \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "LoadBalancerDescriptions[?VPCId=='$vpc_id'].LoadBalancerName" \
            --output text 2>/dev/null); then
            log_debug "Found ELBs in VPC: ${vpc_elbs:-none}"
        else
            log_debug "Failed to query ELBs in VPC (timeout or error)"
            vpc_elbs=""
        fi
        
        # Combine both lists (unique) - handle empty strings properly
        if [[ -n "$elbs" || -n "$vpc_elbs" ]]; then
            elbs=$(echo -e "$elbs\n$vpc_elbs" | tr ' ' '\n' | sort -u | grep -v '^$' | tr '\n' ' ' || true)
        fi
    fi

    # Process any ELBs found
    if [[ -n "$elbs" ]]; then
        for elb in $elbs; do
            if [[ -n "$elb" ]]; then
                if [[ "$DRY_RUN" == "false" ]]; then
                    aws elb delete-load-balancer --load-balancer-name "$elb" \
                        --region "$AWS_REGION" --profile "$AWS_PROFILE"
                    log_info "  Deleted Classic ELB: $elb"
                else
                    log_info "  [DRY RUN] Would delete Classic ELB: $elb"
                fi
            fi
        done
    else
        log_debug "  No Classic ELBs found"
    fi

    # ALBs/NLBs - check both by name pattern AND by VPC association
    local nlbs=""
    
    # First, get by name pattern (with timeout)
    log_debug "Checking for ALB/NLBs by name pattern: $infra_id"
    if nlbs=$(execute_with_timeout 30 aws elbv2 describe-load-balancers \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "LoadBalancers[?contains(LoadBalancerName, '$infra_id')].LoadBalancerArn" \
        --output text 2>/dev/null); then
        log_debug "Found ALB/NLBs by name: ${nlbs:-none}"
    else
        log_debug "Failed to query ALB/NLBs by name (timeout or error)"
        nlbs=""
    fi
    
    # Also get ALL ALB/NLBs in the VPC
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        log_debug "Checking for ALB/NLBs in VPC: $vpc_id"
        local vpc_nlbs=""
        if vpc_nlbs=$(execute_with_timeout 30 aws elbv2 describe-load-balancers \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "LoadBalancers[?VpcId=='$vpc_id'].LoadBalancerArn" \
            --output text 2>/dev/null); then
            log_debug "Found ALB/NLBs in VPC: ${vpc_nlbs:-none}"
        else
            log_debug "Failed to query ALB/NLBs in VPC (timeout or error)"
            vpc_nlbs=""
        fi
        
        # Combine both lists (unique) - handle empty strings properly
        if [[ -n "$nlbs" || -n "$vpc_nlbs" ]]; then
            nlbs=$(echo -e "$nlbs\n$vpc_nlbs" | tr ' ' '\n' | sort -u | grep -v '^$' | tr '\n' ' ' || true)
        fi
    fi

    # Process any ALB/NLBs found
    if [[ -n "$nlbs" ]]; then
        for nlb in $nlbs; do
            if [[ -n "$nlb" ]]; then
                if [[ "$DRY_RUN" == "false" ]]; then
                    aws elbv2 delete-load-balancer --load-balancer-arn "$nlb" \
                        --region "$AWS_REGION" --profile "$AWS_PROFILE"
                    log_info "  Deleted NLB/ALB: $(basename $nlb)"
                else
                    log_info "  [DRY RUN] Would delete NLB/ALB: $(basename $nlb)"
                fi
            fi
        done
    else
        log_debug "  No ALB/NLBs found"
    fi

    # 3. Delete NAT Gateways
    log_info "Step 3/11: Deleting NAT gateways..."
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
    log_info "Step 4/11: Releasing Elastic IPs..."
    local eips=$(aws ec2 describe-addresses \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Addresses[].AllocationId" --output text)

    for eip in $eips; do
        if [[ "$DRY_RUN" == "false" ]]; then
            if execute_with_timeout 30 aws ec2 release-address --allocation-id "$eip" --region "$AWS_REGION" --profile "$AWS_PROFILE"; then
                log_info "  Released Elastic IP: $eip"
            else
                log_warning "  Failed to release Elastic IP: $eip (may already be released)"
            fi
        else
            log_info "  [DRY RUN] Would release Elastic IP: $eip"
        fi
    done

    # Get VPC ID early (we'll need it for multiple steps)
    local vpc_id=$(aws ec2 describe-vpcs \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[0].VpcId" --output text)
    
    # 5. Clean up orphaned network interfaces (from deleted ELBs, etc)
    log_info "Step 5/11: Cleaning up orphaned network interfaces..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        local enis=$(aws ec2 describe-network-interfaces \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "NetworkInterfaces[?Status=='available'].NetworkInterfaceId" \
            --output text)
        
        for eni in $enis; do
            if [[ -n "$eni" ]]; then
                if [[ "$DRY_RUN" == "false" ]]; then
                    if aws ec2 delete-network-interface --network-interface-id "$eni" \
                        --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null; then
                        log_info "  Deleted orphaned network interface: $eni"
                    else
                        log_debug "  Could not delete network interface: $eni (may be in use)"
                    fi
                else
                    log_info "  [DRY RUN] Would delete orphaned network interface: $eni"
                fi
            fi
        done
    fi
    
    # 6. Delete VPC Endpoints (can block subnet/route table deletion)
    log_info "Step 6/11: Deleting VPC endpoints..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        local vpc_endpoints=$(aws ec2 describe-vpc-endpoints \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "VpcEndpoints[].VpcEndpointId" --output text)
        
        for endpoint in $vpc_endpoints; do
            if [[ "$DRY_RUN" == "false" ]]; then
                if aws ec2 delete-vpc-endpoints --vpc-endpoint-ids "$endpoint" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null 2>&1; then
                    log_info "  Deleted VPC Endpoint: $endpoint"
                else
                    log_warning "  Failed to delete VPC Endpoint: $endpoint"
                fi
            else
                log_info "  [DRY RUN] Would delete VPC Endpoint: $endpoint"
            fi
        done
    fi

    # 7. Delete Security Groups (wait for network interfaces to detach)
    if [[ "$DRY_RUN" == "false" ]] && [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        wait_for_network_interfaces "$vpc_id" || log_warning "Proceeding despite network interface timeout"
    fi

    log_info "Step 7/11: Deleting security groups..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        local sgs=$(aws ec2 describe-security-groups \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "SecurityGroups[?GroupName!='default'].GroupId" --output text)

        # Remove all ingress rules first to break circular dependencies
        log_debug "Removing security group ingress rules to break dependencies..."
        for sg in $sgs; do
            if [[ "$DRY_RUN" == "false" ]]; then
                # Get current ingress rules
                local ingress_rules=$(aws ec2 describe-security-groups \
                    --group-ids "$sg" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                    --query 'SecurityGroups[0].IpPermissions' \
                    --output json 2>/dev/null)
                
                if [[ "$ingress_rules" != "[]" && "$ingress_rules" != "null" ]]; then
                    # Revoke all ingress rules at once
                    if ! aws ec2 revoke-security-group-ingress \
                        --group-id "$sg" \
                        --ip-permissions "$ingress_rules" \
                        --region "$AWS_REGION" --profile "$AWS_PROFILE" 2>/dev/null; then
                        log_debug "Some ingress rules for $sg could not be revoked (may be default or already removed)"
                    fi
                fi
            fi
        done

        # Now delete the security groups
        for sg in $sgs; do
            if [[ "$DRY_RUN" == "false" ]]; then
                if execute_with_timeout 30 aws ec2 delete-security-group --group-id "$sg" --region "$AWS_REGION" --profile "$AWS_PROFILE"; then
                    log_info "  Deleted Security Group: $sg"
                else
                    log_warning "  Failed to delete Security Group: $sg (may have dependencies or already deleted)"
                fi
            else
                log_info "  [DRY RUN] Would delete Security Group: $sg"
            fi
        done
    fi

    # 8. Delete Subnets
    log_info "Step 8/11: Deleting subnets..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        local subnets=$(aws ec2 describe-subnets \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Subnets[].SubnetId" --output text)

        for subnet in $subnets; do
            if [[ "$DRY_RUN" == "false" ]]; then
                if execute_with_timeout 30 aws ec2 delete-subnet --subnet-id "$subnet" --region "$AWS_REGION" --profile "$AWS_PROFILE"; then
                    log_info "  Deleted Subnet: $subnet"
                else
                    log_warning "  Failed to delete Subnet: $subnet (may have dependencies)"
                fi
            else
                log_info "  [DRY RUN] Would delete Subnet: $subnet"
            fi
        done
    fi

    # 9. Delete Route Tables (before IGW to avoid dependency issues)
    log_info "Step 9/11: Deleting route tables..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        # Non-main route tables
        local rts=$(aws ec2 describe-route-tables \
            --filters "Name=vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "RouteTables[?Associations[0].Main!=\`true\`].RouteTableId" --output text)

        for rt in $rts; do
            if [[ "$DRY_RUN" == "false" ]]; then
                if execute_with_timeout 30 aws ec2 delete-route-table --route-table-id "$rt" --region "$AWS_REGION" --profile "$AWS_PROFILE"; then
                    log_info "  Deleted Route Table: $rt"
                else
                    log_warning "  Failed to delete Route Table: $rt (may be main route table or have dependencies)"
                fi
            else
                log_info "  [DRY RUN] Would delete Route Table: $rt"
            fi
        done
    fi

    # 10. Delete Internet Gateway
    log_info "Step 10/11: Deleting internet gateway..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        local igw=$(aws ec2 describe-internet-gateways \
            --filters "Name=attachment.vpc-id,Values=$vpc_id" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "InternetGateways[0].InternetGatewayId" --output text)

        if [[ "$igw" != "None" && -n "$igw" ]]; then
            if [[ "$DRY_RUN" == "false" ]]; then
                # Detach IGW first
                log_debug "Detaching Internet Gateway from VPC..."
                if ! execute_with_timeout 30 aws ec2 detach-internet-gateway \
                    --internet-gateway-id "$igw" \
                    --vpc-id "$vpc_id" \
                    --region "$AWS_REGION" \
                    --profile "$AWS_PROFILE" 2>&1 | grep -v "Gateway.NotAttached"; then
                    log_debug "IGW may already be detached or have dependency issues: $igw"
                fi
                
                # Delete IGW
                if execute_with_timeout 30 aws ec2 delete-internet-gateway \
                    --internet-gateway-id "$igw" \
                    --region "$AWS_REGION" \
                    --profile "$AWS_PROFILE"; then
                    log_info "  Deleted Internet Gateway: $igw"
                else
                    log_warning "  Failed to delete Internet Gateway: $igw"
                fi
            else
                log_info "  [DRY RUN] Would detach and delete Internet Gateway: $igw"
            fi
        fi
    fi

    # 11. Delete VPC
    log_info "Step 11/11: Deleting VPC..."
    if [[ "$vpc_id" != "None" && -n "$vpc_id" ]]; then
        if [[ "$DRY_RUN" == "false" ]]; then
            if execute_with_timeout 30 aws ec2 delete-vpc --vpc-id "$vpc_id" --region "$AWS_REGION" --profile "$AWS_PROFILE"; then
                log_info "  Deleted VPC: $vpc_id"
            else
                log_warning "  Failed to delete VPC: $vpc_id (may still have dependencies)"
            fi
        else
            log_info "  [DRY RUN] Would delete VPC: $vpc_id"
        fi
    fi

    # Clean up Route53 DNS records (not numbered as part of main flow)
    log_info "Additional cleanup: Route53 DNS records..."
    cleanup_route53_records "$infra_id"
}

# Manual AWS resource cleanup with reconciliation loop
destroy_aws_resources() {
    local infra_id="$1"
    local max_attempts="${MAX_ATTEMPTS:-5}"
    local attempt=1
    local initial_count=0
    local current_count=0
    local last_count=0
    
    log_info "Starting manual AWS resource cleanup with reconciliation (max attempts: $max_attempts)"
    
    # Get initial resource count
    initial_count=$(count_resources "$infra_id")
    last_count=$initial_count
    
    if [[ "$initial_count" -eq 0 ]]; then
        log_info "No resources found to delete"
        return 0
    fi
    
    log_info "Initial resource count: $initial_count"
    
    # Reconciliation loop
    while [[ $attempt -le $max_attempts ]]; do
        log_info ""
        log_info "${BOLD}=== Reconciliation Attempt $attempt/$max_attempts ===${NC}"
        
        # Run single deletion pass
        destroy_aws_resources_single_pass "$infra_id" "$attempt" "$max_attempts"
        
        # Count remaining resources
        current_count=$(count_resources "$infra_id")
        
        if [[ "$current_count" -eq 0 ]]; then
            log_success "All resources successfully deleted after $attempt attempt(s)"
            return 0
        fi
        
        # Check if we made progress
        local deleted=$((last_count - current_count))
        if [[ "$deleted" -gt 0 ]]; then
            log_info "Deleted $deleted resources in attempt $attempt (remaining: $current_count)"
        else
            log_warning "No progress made in attempt $attempt (remaining: $current_count)"
            
            # If no progress after attempt 3, wait longer between attempts
            if [[ "$attempt" -ge 3 ]]; then
                if [[ "$DRY_RUN" != "true" ]]; then
                    log_info "Waiting 30 seconds before next attempt to allow AWS to process deletions..."
                    sleep 30
                fi
            fi
        fi
        
        last_count=$current_count
        attempt=$((attempt + 1))
        
        # Short wait between attempts (unless we already waited above)
        if [[ "$attempt" -le "$max_attempts" && "$attempt" -lt 3 ]]; then
            if [[ "$DRY_RUN" != "true" ]]; then
                log_info "Waiting 10 seconds before next attempt..."
                sleep 10
            fi
        fi
    done
    
    # Final resource count
    log_warning "Reconciliation completed after $max_attempts attempts"
    log_warning "Resources remaining: $current_count (started with: $initial_count)"
    
    if [[ "$current_count" -gt 0 ]]; then
        log_error "Failed to delete all resources. Manual intervention may be required."
        log_info "Try running with --max-attempts $(($max_attempts + 5)) for more attempts"
        return 1
    fi
    
    return 0
}

# List all OpenShift clusters
list_clusters() {
    log_info ""
    log_info "${BOLD}Searching for OpenShift Clusters${NC}"
    log_info "Region: $AWS_REGION"
    log_info ""

    # Find clusters from EC2 instances (excluding terminated instances)
    log_info "Checking EC2 instances for cluster tags..."
    local ec2_clusters=$(aws ec2 describe-instances \
        --filters "Name=instance-state-name,Values=running,stopped,stopping,pending" \
        --region "$AWS_REGION" \
        --profile "$AWS_PROFILE" \
        --query 'Reservations[].Instances[].Tags[?contains(Key, `kubernetes.io/cluster/`) && Value==`owned`].Key' \
        --output text 2>/dev/null | sed 's/kubernetes.io\/cluster\///g' | sort -u)

    # Find clusters from VPCs
    log_info "Checking VPCs for cluster tags..."
    local vpc_clusters=$(aws ec2 describe-vpcs \
        --region "$AWS_REGION" \
        --profile "$AWS_PROFILE" \
        --query 'Vpcs[].Tags[?contains(Key, `kubernetes.io/cluster/`) && Value==`owned`].Key' \
        --output text 2>/dev/null | sed 's/kubernetes.io\/cluster\///g' | sort -u)

    # Find clusters from S3
    log_info "Checking S3 bucket for cluster states..."
    local s3_clusters=""
    if [[ -n "$S3_BUCKET" ]]; then
        s3_clusters=$(aws s3 ls "s3://${S3_BUCKET}/" \
            --region "$AWS_REGION" \
            --profile "$AWS_PROFILE" 2>/dev/null |
            grep "PRE" | awk '{print $2}' | sed 's/\///')
    fi

    # Combine and deduplicate clusters
    # First, create a mapping of base names to full infra IDs
    declare -A cluster_map
    declare -A s3_state_map
    
    # Process S3 clusters (these are base names)
    for cluster in $s3_clusters; do
        if [[ -n "$cluster" ]]; then
            s3_state_map["$cluster"]="Yes"
            cluster_map["$cluster"]="$cluster"
        fi
    done
    
    # Process AWS clusters (these have full infra IDs)
    for cluster in $(echo -e "$ec2_clusters\n$vpc_clusters" | sort -u | grep -v '^$'); do
        if [[ -n "$cluster" ]]; then
            # Check if this looks like an infra ID (ends with -xxxxx pattern)
            if [[ "$cluster" =~ ^(.+)-([a-z0-9]{5})$ ]]; then
                local base_name="${BASH_REMATCH[1]}"
                # If we already have this base name from S3, update with full infra ID
                if [[ -n "${cluster_map[$base_name]:-}" ]]; then
                    cluster_map["$base_name"]="$cluster"
                else
                    # New cluster not in S3
                    cluster_map["$cluster"]="$cluster"
                fi
            else
                # Doesn't match infra ID pattern, treat as is
                cluster_map["$cluster"]="$cluster"
            fi
        fi
    done
    
    # Get unique clusters
    local all_clusters=$(for key in "${!cluster_map[@]}"; do echo "${cluster_map[$key]}"; done | sort -u)

    if [[ -z "$all_clusters" ]]; then
        log_warning "No OpenShift clusters found in region $AWS_REGION"
        return 1
    fi

    log_info ""
    log_info "${BOLD}Found OpenShift Clusters${NC}"
    log_info ""

    # Display cluster information
    echo "$all_clusters" | while read -r cluster; do
        if [[ -n "$cluster" ]]; then
            # Extract base name for S3 checking
            local base_name="$cluster"
            if [[ "$cluster" =~ ^(.+)-([a-z0-9]{5})$ ]]; then
                base_name="${BASH_REMATCH[1]}"
            fi

            # Quick status check - just see if VPC exists
            local resource_info=""
            if aws ec2 describe-vpcs \
                --filters "Name=tag:kubernetes.io/cluster/$cluster,Values=owned" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                --query "Vpcs[0].VpcId" --output text 2>/dev/null | grep -q "vpc-"; then
                resource_info="Status: Active"
            else
                resource_info="Status: Partial/None"
            fi

            # Check if S3 state exists (using base name)
            local s3_state="No"
            if [[ -n "${s3_state_map[$base_name]:-}" ]]; then
                s3_state="Yes"
            elif [[ -n "$S3_BUCKET" ]] && aws s3 ls "s3://${S3_BUCKET}/${base_name}/" &>/dev/null; then
                s3_state="Yes"
            fi

            # Get creation time from VPC if available
            local created=""
            local vpc_info=$(aws ec2 describe-vpcs \
                --filters "Name=tag:kubernetes.io/cluster/$cluster,Values=owned" \
                --region "$AWS_REGION" \
                --profile "$AWS_PROFILE" \
                --query "Vpcs[0].[VpcId,Tags[?Key=='Name'].Value|[0]]" \
                --output text 2>/dev/null)

            if [[ -n "$vpc_info" ]] && [[ "$vpc_info" != "None" ]]; then
                local vpc_id=$(echo "$vpc_info" | awk '{print $1}')
                # Try to get instance launch time
                local launch_time=$(aws ec2 describe-instances \
                    --filters "Name=tag:kubernetes.io/cluster/$cluster,Values=owned" \
                    "Name=instance-state-name,Values=running,stopped" \
                    --region "$AWS_REGION" \
                    --profile "$AWS_PROFILE" \
                    --query "Reservations[0].Instances[0].LaunchTime" \
                    --output text 2>/dev/null)

                if [[ -n "$launch_time" ]] && [[ "$launch_time" != "None" ]]; then
                    created=" (Created: ${launch_time%T*})"
                fi
            fi

            # Display cluster and infra ID appropriately
            if [[ "$cluster" == "$base_name" ]]; then
                # No separate infra ID (orphaned or custom cluster)
                log_info "  ${BOLD}Cluster:${NC} ${CYAN}${BOLD}$base_name${NC}"
                log_info "    Infrastructure ID: (none - orphaned cluster)"
            else
                # Proper OpenShift cluster with infra ID
                log_info "  ${BOLD}Cluster:${NC} ${CYAN}${BOLD}$base_name${NC}"
                log_info "    Infrastructure ID: $cluster"
            fi
            log_info "    $resource_info"
            log_info "    S3 State: $s3_state$created"
            log_info ""
        fi
    done

    # Show summary
    local cluster_count=$(echo "$all_clusters" | grep -c .)
    log_info ""
    log_info "${BOLD}Summary${NC}"
    log_info "Total clusters found: $cluster_count"

    return 0
}

# Clean up S3 state
# Resolve S3 prefix for cluster - tries cluster name first, then infra-id
resolve_s3_prefix() {
    local cluster_name="$1"
    local infra_id="$2"
    
    # Validate inputs to prevent accidental deletion
    if [[ -z "$cluster_name" && -z "$infra_id" ]]; then
        log_error "Cannot resolve S3 prefix: both cluster_name and infra_id are empty"
        return 1
    fi
    
    # Try cluster name first (preferred)
    if [[ -n "$cluster_name" ]] && aws s3 ls "s3://${S3_BUCKET}/${cluster_name}/" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then
        echo "$cluster_name"
        return 0
    fi
    
    # Try infra-id as fallback
    if [[ -n "$infra_id" ]] && aws s3 ls "s3://${S3_BUCKET}/${infra_id}/" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then
        log_warning "Using infra-id for S3 path (cluster name not found): $infra_id"
        echo "$infra_id"
        return 0
    fi
    
    # Default to cluster name if nothing exists (for new deletions)
    if [[ -n "$cluster_name" ]]; then
        echo "$cluster_name"
    else
        echo "$infra_id"
    fi
}

cleanup_s3_state() {
    local cluster_name="$1"
    local infra_id="$2"
    
    # Resolve the correct S3 prefix
    local s3_prefix=$(resolve_s3_prefix "$cluster_name" "$infra_id")
    if [[ -z "$s3_prefix" ]]; then
        log_error "Failed to resolve S3 prefix for cleanup"
        return 1
    fi

    log_info "Cleaning up S3 state for: $s3_prefix"

    if aws s3 ls "s3://${S3_BUCKET}/${s3_prefix}/" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then

        if [[ "$DRY_RUN" == "false" ]]; then
            aws s3 rm "s3://${S3_BUCKET}/${s3_prefix}/" --recursive \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" >/dev/null
            log_success "Deleted S3 state: s3://${S3_BUCKET}/${s3_prefix}/"
        else
            log_info "[DRY RUN] Would delete S3 state: s3://${S3_BUCKET}/${s3_prefix}/"
        fi
    else
        log_info "No S3 state found for: $s3_prefix"
    fi
}

# Show detailed list of resources to be deleted
show_resource_details() {
    local infra_id="$1"
    local cluster_name="${CLUSTER_NAME:-${infra_id%-*}}"

    echo ""
    log_info "${BOLD}$([ "$DRY_RUN" == "true" ] && echo "RESOURCES THAT WOULD BE DELETED:" || echo "RESOURCES TO BE DELETED:")${NC}"
    echo ""

    # List EC2 Instances
    local instances=$(aws ec2 describe-instances \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        "Name=instance-state-name,Values=running,stopped,stopping,pending" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Reservations[].Instances[].[InstanceId,InstanceType,Tags[?Key=='Name'].Value|[0]]" \
        --output text 2>/dev/null)

    if [[ -n "$instances" ]]; then
        log_info "EC2 Instances:"
        echo "$instances" | while read -r id type name; do
            log_info "  - $id ($type) - $name"
        done
    fi

    # List Load Balancers
    local nlbs=$(aws elbv2 describe-load-balancers \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "LoadBalancers[?contains(LoadBalancerName, '$infra_id')].[LoadBalancerName,Type]" \
        --output text 2>/dev/null)

    if [[ -n "$nlbs" ]]; then
        log_info "Load Balancers:"
        echo "$nlbs" | while read -r name type; do
            log_info "  - $name ($type)"
        done
    fi

    # List NAT Gateways
    local nats=$(aws ec2 describe-nat-gateways \
        --filter "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "NatGateways[?State!='deleted'].[NatGatewayId,State]" \
        --output text 2>/dev/null)

    if [[ -n "$nats" ]]; then
        log_info "NAT Gateways:"
        echo "$nats" | while read -r id state; do
            log_info "  - $id ($state)"
        done
    fi

    # List Elastic IPs
    local eips=$(aws ec2 describe-addresses \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Addresses[].[AllocationId,PublicIp]" \
        --output text 2>/dev/null)

    if [[ -n "$eips" ]]; then
        log_info "Elastic IPs:"
        echo "$eips" | while read -r id ip; do
            log_info "  - $id ($ip)"
        done
    fi

    # List VPC and related resources
    local vpc=$(aws ec2 describe-vpcs \
        --filters "Name=tag:kubernetes.io/cluster/$infra_id,Values=owned" \
        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
        --query "Vpcs[0].[VpcId,CidrBlock]" \
        --output text 2>/dev/null)

    if [[ -n "$vpc" && "$vpc" != "None" ]]; then
        log_info "VPC:"
        log_info "  - $(echo $vpc | awk '{print $1}') ($(echo $vpc | awk '{print $2}')})"

        # Count subnets
        local subnet_count=$(aws ec2 describe-subnets \
            --filters "Name=vpc-id,Values=$(echo $vpc | awk '{print $1}')" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "Subnets | length(@)" --output text 2>/dev/null)
        log_info "    - $subnet_count subnets"

        # Count security groups
        local sg_count=$(aws ec2 describe-security-groups \
            --filters "Name=vpc-id,Values=$(echo $vpc | awk '{print $1}')" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "SecurityGroups | length(@)" --output text 2>/dev/null)
        log_info "    - $sg_count security groups"

        # Count route tables
        local rt_count=$(aws ec2 describe-route-tables \
            --filters "Name=vpc-id,Values=$(echo $vpc | awk '{print $1}')" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" \
            --query "RouteTables | length(@)" --output text 2>/dev/null)
        log_info "    - $rt_count route tables"
    fi

    # Check S3 resources
    show_s3_resources

    # Don't return resource counts in the output stream
}


# Show S3 resources
show_s3_resources() {
    if [[ -n "$CLUSTER_NAME" ]]; then
        if aws s3 ls "s3://${S3_BUCKET}/${CLUSTER_NAME}/" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then
            log_info "S3 State:"
            log_info "  - s3://${S3_BUCKET}/${CLUSTER_NAME}/"
        fi
    fi
}

# Get user confirmation for destruction
get_user_confirmation() {
    local total_resources="$1"

    echo ""
    log_info "${BOLD}TOTAL: Approximately $total_resources AWS resources $([ "$DRY_RUN" == "true" ] && echo "would be" || echo "will be") deleted${NC}"

    # Debug output for troubleshooting
    log_debug "About to check confirmation: DRY_RUN=$DRY_RUN, FORCE=$FORCE"

    # Show confirmation only in normal mode (not dry-run)
    if [[ "$DRY_RUN" != "true" ]]; then
        log_debug "DRY_RUN is not true, checking FORCE..."
        if [[ "$FORCE" != "true" ]]; then
            log_debug "FORCE is not true, showing confirmation prompt..."
            echo ""
            log_warning "[!] THIS ACTION CANNOT BE UNDONE!"
            echo ""
            # Debug: check if stdin is available
            if [[ -t 0 ]]; then
                read -p "Are you sure you want to destroy ALL the above resources? Type 'yes' to continue: " -r confirm
            else
                log_error "Cannot read confirmation: stdin is not a terminal"
                log_error "Use --force to skip confirmation or run script interactively"
                exit 1
            fi
            if [[ "$confirm" != "yes" ]]; then
                log_warning "Destruction cancelled by user"
                exit 0
            fi
        fi
    fi
}

# Select and execute destruction method
execute_destruction() {
    local infra_id="$1"
    local use_openshift_install=false

    # Priority order for destruction methods:
    # 1. Try openshift-install with S3 state (if available)
    # 2. Fall back to manual AWS cleanup

    # Try openshift-install if we have cluster name and S3 state
    if [[ -n "$CLUSTER_NAME" ]]; then
        log_info "Checking for S3 state to use openshift-install..."

        # Check if S3 has cluster state
        if aws s3 ls "s3://${S3_BUCKET}/${CLUSTER_NAME}/cluster-state.tar.gz" \
            --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then

            log_info "Found cluster state in S3, downloading for openshift-install..."

            local temp_dir="$(mktemp -d -t "openshift-destroy-${CLUSTER_NAME}.XXXXXX")"

            # Download all cluster state from S3
            if aws s3 sync "s3://${S3_BUCKET}/${CLUSTER_NAME}/" "$temp_dir/" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" --quiet; then

                # Extract cluster-state.tar.gz if it exists
                if [[ -f "$temp_dir/cluster-state.tar.gz" ]]; then
                    log_info "Extracting cluster state archive..."
                    if tar -xzf "$temp_dir/cluster-state.tar.gz" -C "$temp_dir" 2>/dev/null; then
                        log_info "Successfully extracted cluster state archive"
                        # Move the extracted cluster directory contents up one level
                        if [[ -d "$temp_dir/${CLUSTER_NAME}" ]]; then
                            mv "$temp_dir/${CLUSTER_NAME}"/* "$temp_dir/" 2>/dev/null || true
                            rmdir "$temp_dir/${CLUSTER_NAME}" 2>/dev/null || true
                        fi
                    else
                        log_warning "Failed to extract cluster-state.tar.gz"
                    fi
                fi

                if [[ -f "$temp_dir/metadata.json" ]]; then
                    log_info "Successfully downloaded cluster state, using openshift-install..."

                    # Extract infrastructure ID from metadata if not already set
                    if [[ -z "$INFRA_ID" ]]; then
                        INFRA_ID=$(grep -o '"infraID"[[:space:]]*:[[:space:]]*"[^"]*"' "$temp_dir/metadata.json" 2>/dev/null | sed 's/.*:.*"\([^"]*\)".*/\1/')
                        if [[ -n "$INFRA_ID" ]]; then
                            log_info "Extracted infrastructure ID: $INFRA_ID"
                        fi
                    fi

                    # Try openshift-install destroy
                    if destroy_with_openshift_install "$temp_dir"; then
                        use_openshift_install=true
                    else
                        log_warning "openshift-install destroy failed, falling back to manual cleanup"
                    fi

                    # Clean up temp directory
                    rm -rf "$temp_dir"
                else
                    log_warning "metadata.json not found in S3 state, using manual cleanup"
                fi
            else
                log_warning "Failed to download S3 state, using manual cleanup"
            fi
        else
            log_info "No S3 state found for cluster: $CLUSTER_NAME"
        fi
    fi

    # Fall back to manual cleanup if openshift-install wasn't used or failed
    if [[ "$use_openshift_install" != "true" ]]; then
        log_info "Running comprehensive AWS resource cleanup..."
        destroy_aws_resources "$infra_id"
    else
        # Even with successful openshift-install, we need to clean up Route53
        # as openshift-install sometimes leaves DNS records behind
        log_info "Cleaning up Route53 records (post openshift-install)..."
        cleanup_route53_records "$infra_id"
    fi

    # Clean up S3 state
    cleanup_s3_state "$CLUSTER_NAME" "$infra_id"

    # Post-destruction verification
    echo ""
    log_info "Post-destruction verification..."
    local remaining_count=$(count_resources "$infra_id")

    if [[ "$remaining_count" -gt 0 ]]; then
        log_warning "$remaining_count resources may still exist. Check AWS console."
    else
        log_success "All resources successfully deleted"
    fi
}

# Main execution
main() {
    # Parse arguments first (before logging setup)
    parse_args "$@"
    
    # Setup colors based on user preference
    setup_colors
    
    # Initialize logging after parsing arguments (so we have LOG_FILE and LOGGING_ENABLED set)
    setup_logging
    
    # Now we can start logging - organize the preamble
    log_info ""
    log_info "${BOLD}OpenShift Cluster Destroyer${NC}"
    log_info "Started: $(date)"
    log_info ""
    
    # Show logging configuration  
    if [[ "$LOGGING_ENABLED" == "true" ]]; then
        log_info "Logging: Enabled"
        log_info "Log file: $LOG_FILE"
        if [[ "$CLOUDWATCH_ENABLED" == "true" ]]; then
            log_info "CloudWatch: $CLOUDWATCH_LOG_GROUP/$CLOUDWATCH_LOG_STREAM"
        fi
    fi
    # Don't show anything if logging is disabled (default) to keep output clean
    
    log_info ""
    
    # Validate inputs
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
            log_warning "Could not find valid infrastructure ID for cluster: $CLUSTER_NAME"
            
            # Check if S3 state exists even without valid metadata
            if aws s3 ls "s3://${S3_BUCKET}/${CLUSTER_NAME}/" \
                --region "$AWS_REGION" --profile "$AWS_PROFILE" &>/dev/null; then
                log_info "Found S3 state for cluster, will attempt cleanup with cluster name as ID"
                # Use cluster name as fallback infra ID for orphaned resources
                INFRA_ID="$CLUSTER_NAME"
            else
                # Check for any AWS resources with cluster name as prefix (e.g., cluster-name-xxxxx)
                log_info "Searching for AWS resources with cluster name prefix..."
                
                # Check VPCs first
                local found_infra_ids=$(aws ec2 describe-vpcs \
                    --filters "Name=tag-key,Values=kubernetes.io/cluster/${CLUSTER_NAME}*" \
                    --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                    --query "Vpcs[].Tags[?starts_with(Key, 'kubernetes.io/cluster/${CLUSTER_NAME}')].Key" \
                    --output text 2>/dev/null | sed "s|kubernetes.io/cluster/||g" | head -1)
                
                # If no VPCs, check instances (excluding terminated ones)
                if [[ -z "$found_infra_ids" ]]; then
                    found_infra_ids=$(aws ec2 describe-instances \
                        --filters "Name=tag-key,Values=kubernetes.io/cluster/${CLUSTER_NAME}*" \
                        "Name=instance-state-name,Values=running,stopped,stopping,pending" \
                        --region "$AWS_REGION" --profile "$AWS_PROFILE" \
                        --query "Reservations[].Instances[].Tags[?starts_with(Key, 'kubernetes.io/cluster/${CLUSTER_NAME}')].Key" \
                        --output text 2>/dev/null | sed "s|kubernetes.io/cluster/||g" | sort -u | head -1)
                fi
                
                if [[ -n "$found_infra_ids" ]]; then
                    INFRA_ID="$found_infra_ids"
                    log_info "Found orphaned infrastructure with ID: $INFRA_ID"
                    log_warning "This appears to be an orphaned cluster without S3 state"
                else
                    log_error "No infrastructure ID, S3 state, or AWS resources found for cluster: $CLUSTER_NAME"
                    log_info "The cluster might not exist or might already be deleted"
                    exit 1
                fi
            fi
        fi
    fi

    # Ensure we have an infrastructure ID at this point
    if [[ -z "$INFRA_ID" ]]; then
        log_error "No infrastructure ID found or provided"
        exit 1
    fi

    # Show cluster summary
    log_info ""
    log_info "${BOLD}Target Cluster Information${NC}"
    log_info "Cluster Name:      ${CYAN}${BOLD}${CLUSTER_NAME:-unknown}${NC}"
    log_info "Infrastructure ID: $INFRA_ID"
    log_info "AWS Region:        $AWS_REGION"
    log_info "AWS Profile:       $AWS_PROFILE"
    log_info "Base Domain:       $BASE_DOMAIN"
    log_info "Mode:              $([ "$DRY_RUN" == "true" ] && echo "DRY RUN" || echo "LIVE")"
    log_info "Max Attempts:      $MAX_ATTEMPTS"
    log_info ""

    # Count total resources
    local resource_count=$(count_resources "$INFRA_ID")
    log_info "Total AWS resources found: $resource_count"

    # Handle no resources case
    if [[ "$resource_count" -eq 0 ]]; then
        log_warning "No AWS resources found for this cluster"
        cleanup_s3_state "$CLUSTER_NAME" "$INFRA_ID"
        log_success "Cluster cleanup completed (no resources to delete)"
        exit 0
    fi

    # Show detailed resource list
    show_resource_details "$INFRA_ID"

    # Get user confirmation if needed (using the already counted resources)
    get_user_confirmation "$resource_count"

    # Execute destruction
    execute_destruction "$INFRA_ID"

    log_info ""
    log_info "${BOLD}Operation Completed${NC}"
    log_info "Finished: $(date)"
    if [[ "$LOGGING_ENABLED" == "true" ]]; then
        log_info "Full log: $LOG_FILE"
    fi
}

# Run main function
main "$@"

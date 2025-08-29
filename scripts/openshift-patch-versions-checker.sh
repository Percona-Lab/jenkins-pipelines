#!/bin/bash

# Script to show the last 5 patch versions for each OpenShift minor version
# This demonstrates what versions would be available with the new implementation

set -euo pipefail

# Check for required tools
command -v curl >/dev/null 2>&1 || { echo "Error: curl is required but not installed. Please install curl and try again."; exit 1; }

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${BLUE}============================================================${NC}"
echo -e "${BLUE}OpenShift Last 5 Patch Versions per Minor Release${NC}"
echo -e "${BLUE}============================================================${NC}"
echo ""

# Versions to check (4.16 to 4.19)
VERSIONS=("4.19" "4.18" "4.17" "4.16")
CHANNELS=("stable" "fast" "candidate")

# Function to get last 5 patches for a version/channel
get_last_5_patches() {
    local channel=$1
    local version=$2
    
    # Make API call with status code capture
    local response=$(curl -sH 'Accept: application/json' \
        --max-time 10 \
        -w '\n%{http_code}' \
        "https://api.openshift.com/api/upgrades_info/v1/graph?channel=${channel}-${version}" 2>/dev/null)
    
    # Extract HTTP status code (last line)
    local http_code=$(echo "$response" | tail -1)
    
    # Extract JSON response (all but last line)
    local json_response=$(echo "$response" | sed '$d')
    
    # Check HTTP status code
    if [[ "$http_code" != "200" ]]; then
        if [[ "$http_code" == "000" ]]; then
            echo "Warning: Connection timeout or network error for ${channel}-${version}" >&2
        else
            echo "Warning: API returned HTTP $http_code for ${channel}-${version}" >&2
        fi
        echo ""
        return
    fi
    
    # Parse JSON response
    # Extract versions from JSON - looking for patterns like "version":"4.16.5"
    local output=$(echo "$json_response" | \
        grep -o '"version":"[^"]*"' | \
        sed 's/"version":"\([^"]*\)"/\1/' | \
        sort -V | \
        tail -5)
    
    echo "$output"
}

# Function to get ALL patches for comparison
get_all_patches() {
    local channel=$1
    local version=$2
    
    # Make API call with status code capture
    local response=$(curl -sH 'Accept: application/json' \
        --max-time 10 \
        -w '\n%{http_code}' \
        "https://api.openshift.com/api/upgrades_info/v1/graph?channel=${channel}-${version}" 2>/dev/null)
    
    # Extract HTTP status code (last line)
    local http_code=$(echo "$response" | tail -1)
    
    # Extract JSON response (all but last line)
    local json_response=$(echo "$response" | sed '$d')
    
    # Check HTTP status code
    if [[ "$http_code" != "200" ]]; then
        echo "0"  # Return 0 count on error
        return
    fi
    
    # Parse JSON response
    # Count the number of version entries in the JSON
    local output=$(echo "$json_response" | \
        grep -o '"version":"[^"]*"' | \
        wc -l | \
        tr -d ' ')
    
    echo "$output"
}

# Check each channel
for channel in "${CHANNELS[@]}"; do
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}Channel: ${channel}${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    
    for version in "${VERSIONS[@]}"; do
        echo -e "${YELLOW}OpenShift ${version} (${channel} channel)${NC}"
        echo -e "${YELLOW}─────────────────────────────${NC}"
        
        # Get total count
        total_count=$(get_all_patches "$channel" "$version")
        
        # Get last 5 patches
        patches=$(get_last_5_patches "$channel" "$version")
        
        if [[ -n "$patches" ]]; then
            echo -e "${GREEN}Total available: ${total_count} versions${NC}"
            echo -e "${GREEN}Last 5 patches:${NC}"
            echo "$patches" | while IFS= read -r patch; do
                echo "  • $patch"
            done
            
            # Show the latest (last one)
            latest=$(echo "$patches" | tail -1)
            echo -e "${BLUE}  └─> Latest: ${latest}${NC}"
        else
            echo -e "${RED}  No versions available${NC}"
        fi
        echo ""
    done
done

# Summary showing what would be selected
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}Summary: Latest Version per Channel${NC}"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

for channel in "${CHANNELS[@]}"; do
    echo -e "${YELLOW}${channel} channel:${NC}"
    
    # Collect all latest versions from each minor version
    all_latest=()
    for version in "${VERSIONS[@]}"; do
        patches=$(get_last_5_patches "$channel" "$version")
        if [[ -n "$patches" ]]; then
            latest=$(echo "$patches" | tail -1)
            all_latest+=("$latest")
        fi
    done
    
    if [[ ${#all_latest[@]} -gt 0 ]]; then
        # Sort and get the absolute latest
        latest_overall=$(printf '%s\n' "${all_latest[@]}" | sort -V | tail -1)
        echo -e "  ${GREEN}✓ Would select: ${latest_overall}${NC}"
        echo "    (from last 5 patches of each minor version)"
    else
        echo -e "  ${RED}✗ No versions available${NC}"
    fi
    echo ""
done

echo -e "${BLUE}============================================================${NC}"
echo -e "${BLUE}Configuration for Jenkins:${NC}"
echo -e "${BLUE}============================================================${NC}"
echo ""
echo "export OPENSHIFT_MAJOR_MINOR_VERSIONS=\"4.19,4.18,4.17,4.16\""
echo "export OPENSHIFT_FALLBACK_VERSION=\"4.16.45\""
echo ""
echo "With this configuration:"
echo "• Only checks versions from 4.16 to 4.19"
echo "• Retrieves only the last 5 patches per minor version"
echo "• Reduces API calls and response size"
echo "• Always gets the most recent stable patches"

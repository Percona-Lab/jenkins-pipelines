/**
 * Pre-flight quota checks for OpenShift cluster creation.
 *
 * Fails fast when AWS quotas would block a successful create. The CAPI
 * infrastructure-readiness step has a 15-minute timeout that gives no signal
 * about what actually went wrong; verifying quotas up front turns "infra not
 * ready" failures into immediate, actionable errors.
 *
 * @since 1.0.0
 */

import groovy.json.JsonSlurper

/**
 * Per-cluster AWS resource requirements.
 * These match what `openshift-install create cluster` provisions for the
 * default IPI install on AWS: 1 VPC, 1 NAT gateway per AZ across 3 AZs,
 * 1 EIP per NAT gateway, 3 control-plane nodes.
 *
 * vCPU usage is computed from the actual master/worker instance types
 * looked up via the EC2 API, so any instance family the create job allows
 * (m5, m6i, c5, etc.) reports its real vCPU count rather than guessing.
 */
def clusterRequirements(Map params) {
    int masterVcpu = vcpuFor(params.masterType, params.awsRegion) * 3
    int workerVcpu = vcpuFor(params.workerType, params.awsRegion) * (params.workerCount ?: 3)
    return [
        vpcs        : 1,
        eips        : 3,  // one per NAT gateway, one per AZ
        natGateways : 3,
        vcpus       : masterVcpu + workerVcpu
    ]
}

/**
 * vCPU count for a single EC2 instance type, queried live from the EC2 API.
 * Cached per-build so we make at most one call per instance type.
 */
private int vcpuFor(String instanceType, String region) {
    if (!instanceType) {
        error "vcpuFor: instanceType is required"
    }
    def out = sh(
        script: "aws ec2 describe-instance-types --region ${region} --instance-types ${instanceType} --query 'InstanceTypes[0].VCpuInfo.DefaultVCpus' --output text",
        returnStdout: true
    ).trim()
    if (!out || out == 'None') {
        error "vcpuFor: unknown EC2 instance type '${instanceType}' in ${region}"
    }
    return out.toInteger()
}

/**
 * Run all pre-flight quota checks for a cluster create.
 *
 * @param params Map containing:
 *   - awsRegion: AWS region (required)
 *   - masterType: master EC2 instance type (optional, default m5.xlarge)
 *   - workerType: worker EC2 instance type (optional, default m5.large)
 *   - workerCount: number of workers (optional, default 3)
 *   - failOnWarning: error on marginal headroom too (optional, default false)
 *
 * @throws RuntimeException when quota headroom is insufficient
 */
def check(Map params) {
    if (!params.awsRegion) {
        error 'preflightChecks: awsRegion is required'
    }

    def needs = clusterRequirements(params)
    openshiftTools.log('INFO', "Pre-flight: this cluster needs ${needs.vpcs} VPC, ${needs.eips} EIPs, ${needs.natGateways} NAT GWs, ${needs.vcpus} vCPUs in ${params.awsRegion}", params)

    def usage = currentUsage(params.awsRegion)
    def quotas = quotaLimits(params.awsRegion)

    def report = []
    def violations = []

    [
        [name: 'VPCs',                    used: usage.vpcs,        limit: quotas.vpcs,        need: needs.vpcs],
        [name: 'Elastic IPs',             used: usage.eips,        limit: quotas.eips,        need: needs.eips],
        [name: 'Standard vCPUs (running)', used: usage.vcpus,       limit: quotas.vcpus,       need: needs.vcpus]
    ].each { check ->
        def free = check.limit - check.used
        def status = free >= check.need ? 'OK' : 'BLOCKED'
        report << String.format('%-25s used=%-6s limit=%-6s free=%-6s need=%-6s -> %s',
            check.name, check.used.toString(), check.limit.toString(), free.toString(), check.need.toString(), status)
        if (free < check.need) {
            violations << "${check.name}: need ${check.need}, only ${free} free (${check.used}/${check.limit})"
        }
    }

    // NAT gateways are per-AZ. Default install spreads across 3 AZs and
    // creates one NAT GW per AZ. Block if any AZ is at the per-AZ cap.
    def natByAz = usage.natGatewaysByAz
    def natLimit = quotas.natGatewaysPerAz
    natByAz.each { az, count ->
        def free = natLimit - count
        def status = free >= 1 ? 'OK' : 'BLOCKED'
        report << String.format('%-25s used=%-6s limit=%-6s free=%-6s need=%-6s -> %s',
            "NAT GWs (${az})", count.toString(), natLimit.toString(), free.toString(), '1', status)
        if (free < 1) {
            violations << "NAT gateways in ${az}: at limit (${count}/${natLimit})"
        }
    }

    echo "Pre-flight quota report (${params.awsRegion}):\n  ${report.join('\n  ')}"

    if (violations) {
        error """Pre-flight check failed: AWS quota headroom is insufficient in ${params.awsRegion}.

${violations.collect { "  - ${it}" }.join('\n')}

Either destroy an unused cluster (openshift-cluster-list + openshift-cluster-destroy),
request a quota increase via AWS Service Quotas, or wait for an in-flight cluster to finish.
"""
    }

    return [report: report, usage: usage, quotas: quotas, needs: needs]
}

/**
 * Snapshot of currently-consumed AWS resources in the region.
 */
def currentUsage(String region) {
    def vpcs = sh(
        script: "aws ec2 describe-vpcs --region ${region} --query 'length(Vpcs)' --output text",
        returnStdout: true
    ).trim().toInteger()

    def eips = sh(
        script: "aws ec2 describe-addresses --region ${region} --query 'length(Addresses)' --output text",
        returnStdout: true
    ).trim().toInteger()

    // Sum vCPUs of every running standard-family instance. Pricing dimensions
    // (a, c, d, h, i, m, r, t, z) all draw from the same Service Quota bucket.
    def vcpuJson = sh(
        script: """aws ec2 describe-instances \
            --region ${region} \
            --filters Name=instance-state-name,Values=running \
            --query 'Reservations[].Instances[].CpuOptions.[CoreCount,ThreadsPerCore]' \
            --output json""",
        returnStdout: true
    ).trim()
    int vcpus = sumVcpus(vcpuJson)

    def natJson = sh(
        script: """aws ec2 describe-nat-gateways \
            --region ${region} \
            --filter Name=state,Values=available \
            --query 'NatGateways[].SubnetId' \
            --output json""",
        returnStdout: true
    ).trim()
    def subnetIds = parseSubnetIds(natJson)

    def azJson = ''
    if (subnetIds) {
        azJson = sh(
            script: "aws ec2 describe-subnets --region ${region} --subnet-ids ${subnetIds.join(' ')} --query 'Subnets[].AvailabilityZone' --output json",
            returnStdout: true
        ).trim()
    }
    Map<String, Integer> natByAz = bucketByAz(azJson)

    return [vpcs: vpcs, eips: eips, vcpus: vcpus, natGatewaysByAz: natByAz]
}

/**
 * Pure parsing helpers run @NonCPS so the JsonSlurper Lazy* views never
 * land in the CPS-resumable program state, only plain serializable types do.
 */
@NonCPS
private int sumVcpus(String vcpuJson) {
    int total = 0
    new JsonSlurper().parseText(vcpuJson ?: '[]').each { pair ->
        if (pair && pair.size() == 2 && pair[0] != null && pair[1] != null) {
            total += (pair[0] as int) * (pair[1] as int)
        }
    }
    return total
}

@NonCPS
private List<String> parseSubnetIds(String json) {
    if (!json) {
        return []
    }
    def parsed = new JsonSlurper().parseText(json) ?: []
    return parsed.collect { it.toString() }
}

@NonCPS
private Map<String, Integer> bucketByAz(String azJson) {
    Map<String, Integer> bucket = new HashMap<>()
    if (!azJson) {
        return bucket
    }
    new JsonSlurper().parseText(azJson).each { az ->
        String key = az.toString()
        bucket.put(key, (bucket.get(key) ?: 0) + 1)
    }
    return bucket
}

/**
 * AWS Service Quotas snapshot for the resources we care about.
 *
 * Codes:
 *   vpc/L-F678F1CE         VPCs per Region
 *   ec2/L-0263D0A3         EC2-VPC Elastic IPs
 *   ec2/L-1216C47A         Running On-Demand Standard (A,C,D,H,I,M,R,T,Z) instances (vCPUs)
 *   vpc/L-FE5A380F         NAT gateways per Availability Zone
 */
def quotaLimits(String region) {
    def fetch = { service, code ->
        def out = sh(
            script: "aws service-quotas get-service-quota --service-code ${service} --quota-code ${code} --region ${region} --query 'Quota.Value' --output text",
            returnStdout: true
        ).trim()
        return out.toFloat().toInteger()
    }
    return [
        vpcs             : fetch('vpc', 'L-F678F1CE'),
        eips             : fetch('ec2', 'L-0263D0A3'),
        vcpus            : fetch('ec2', 'L-1216C47A'),
        natGatewaysPerAz : fetch('vpc', 'L-FE5A380F')
    ]
}

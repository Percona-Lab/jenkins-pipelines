def call(Map params) {
    echo 'Validating OpenShift cluster parameters...'

    def errors = []

    // Validate cluster name
    if (!params.clusterName) {
        errors << 'Cluster name is required'
    } else if (!isValidClusterName(params.clusterName)) {
        errors << "Invalid cluster name: ${params.clusterName}. Must be lowercase alphanumeric with hyphens, max 63 chars"
    }

    // Validate OpenShift version
    if (!params.openshiftVersion) {
        errors << 'OpenShift version is required'
    } else if (!isValidVersion(params.openshiftVersion)) {
        errors << "Invalid OpenShift version: ${params.openshiftVersion}"
    }

    // Validate AWS region
    if (!params.awsRegion) {
        errors << 'AWS region is required'
    } else if (!isValidRegion(params.awsRegion)) {
        errors << "Invalid AWS region: ${params.awsRegion}"
    }

    // Validate instance types
    if (!params.masterType) {
        errors << 'Master instance type is required'
    } else if (!isValidInstanceType(params.masterType)) {
        errors << "Invalid master instance type: ${params.masterType}"
    }

    if (!params.workerType) {
        errors << 'Worker instance type is required'
    } else if (!isValidInstanceType(params.workerType)) {
        errors << "Invalid worker instance type: ${params.workerType}"
    }

    // Validate worker count
    if (!params.workerCount) {
        errors << 'Worker count is required'
    } else {
        def count = params.workerCount.toString().toInteger()
        if (count < 2 || count > 10) {
            errors << "Worker count must be between 2 and 10, got: ${count}"
        }
    }

    // Validate base domain
    if (!params.baseDomain) {
        errors << 'Base domain is required'
    } else if (!isValidDomain(params.baseDomain)) {
        errors << "Invalid base domain: ${params.baseDomain}"
    }

    // Validate delete after hours
    if (params.deleteAfterHours) {
        def hours = params.deleteAfterHours.toString().toInteger()
        if (hours < 1 || hours > 168) {
            errors << "Delete after hours must be between 1 and 168 (1 week), got: ${hours}"
        }
    }

    // Validate spot instance configuration
    if (params.useSpotInstances && params.spotMaxPrice) {
        if (!isValidSpotPrice(params.spotMaxPrice)) {
            errors << "Invalid spot max price: ${params.spotMaxPrice}. Must be a positive number or empty"
        }
    }

    // Validate credentials
    if (!params.pullSecret || params.pullSecret.trim().isEmpty()) {
        errors << 'Pull secret is required for OpenShift installation'
    }

    if (!params.sshPublicKey || params.sshPublicKey.trim().isEmpty()) {
        errors << 'SSH public key is required for cluster access'
    }

    // If there are errors, fail the validation
    if (errors) {
        def errorMessage = 'Parameter validation failed:\n' + errors.collect { "  - ${it}" }.join('\n')
        error errorMessage
    }

    echo 'All parameters validated successfully'
    return true
}

def isValidClusterName(String name) {
    // OpenShift cluster name requirements:
    // - Lowercase letters, numbers, and hyphens only
    // - Must start with a letter
    // - Must end with a letter or number
    // - Maximum 63 characters
    return name.matches(/^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$/)
}

def isValidVersion(String version) {
    // Accept 'latest' or semantic version
    if (version == 'latest' || version == 'stable') {
        return true
    }

    // Check semantic version format
    if (!version.matches(/^\d+\.\d+\.\d+$/)) {
        return false
    }

    // Check if version is supported
    def parts = version.tokenize('.')
    def major = parts[0].toInteger()
    def minor = parts[1].toInteger()

    // OpenShift 4.x versions
    if (major != 4) {
        return false
    }

    // Supported minor versions (adjust as needed)
    def supportedMinorVersions = [16, 17, 18, 19]
    return supportedMinorVersions.contains(minor)
}

def isValidRegion(String region) {
    def validRegions = [
        'us-east-1', 'us-east-2', 'us-west-1', 'us-west-2',
        'ca-central-1',
        'eu-west-1', 'eu-west-2', 'eu-west-3', 'eu-central-1', 'eu-north-1',
        'ap-southeast-1', 'ap-southeast-2', 'ap-northeast-1', 'ap-northeast-2',
        'ap-south-1',
        'sa-east-1'
    ]
    return validRegions.contains(region)
}

def isValidInstanceType(String instanceType) {
    // Common x86_64 instance families for OpenShift
    def validPatterns = [
        ~/^m[56]i?\.[a-z0-9]+$/,     // m5, m6i series
        ~/^m[56]a\.[a-z0-9]+$/,      // m5a, m6a series (AMD)
        ~/^c[56]i?\.[a-z0-9]+$/,     // c5, c6i series
        ~/^c[56]a\.[a-z0-9]+$/,      // c5a, c6a series (AMD)
        ~/^r[56]i?\.[a-z0-9]+$/,     // r5, r6i series
        ~/^r[56]a\.[a-z0-9]+$/,      // r5a, r6a series (AMD)
        ~/^t3\.[a-z0-9]+$/,          // t3 series (burstable)
        ~/^t4g\.[a-z0-9]+$/          // t4g series (ARM, but included for completeness)
    ]

    return validPatterns.any { pattern -> instanceType.matches(pattern) }
}

def isValidDomain(String domain) {
    // Basic domain validation
    return domain.matches(/^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$/)
}

def isValidSpotPrice(String price) {
    if (!price || price.trim().isEmpty()) {
        return true  // Empty means use on-demand price as max
    }

    try {
        def priceValue = price.toDouble()
        return priceValue > 0 && priceValue < 100  // Reasonable price range
    } catch (NumberFormatException e) {
        return false
    }
}

// Method to sanitize cluster name
def sanitizeClusterName(String name) {
    // Convert to lowercase and replace invalid characters with hyphens
    def sanitized = name.toLowerCase().replaceAll(/[^a-z0-9-]/, '-')

    // Ensure it starts with a letter
    if (!sanitized.matches(/^[a-z].*/)) {
        sanitized = 'oc-' + sanitized
    }

    // Ensure it ends with alphanumeric
    sanitized = sanitized.replaceAll(/-+$/, '')

    // Truncate if too long
    if (sanitized.length() > 63) {
        sanitized = sanitized.substring(0, 63).replaceAll(/-+$/, '')
    }

    return sanitized
}

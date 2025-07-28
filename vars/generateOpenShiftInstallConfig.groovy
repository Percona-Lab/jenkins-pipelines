def call(Map config) {
    // Validate required parameters
    def required = ['baseDomain', 'workerType', 'workerCount', 'masterType', 'clusterName',
                    'awsRegion', 'deleteAfterHours', 'teamName', 'productTag', 'buildUser',
                    'timestamp', 'pullSecret', 'sshPublicKey']

    required.each { param ->
        if (!config.containsKey(param) || config[param] == null || config[param].toString().trim().isEmpty()) {
            error "Missing required parameter: ${param}"
        }
    }

    // Handle spot instance configuration
    def spotConfig = ''
    if (config.spotConfig) {
        spotConfig = config.spotConfig
    }

    // Generate the install-config YAML
    def installConfig = """apiVersion: v1
baseDomain: ${config.baseDomain}
compute:
- architecture: amd64
  hyperthreading: Enabled
  name: worker
  platform:
    aws:
      type: ${config.workerType}${spotConfig ? '\n      ' + spotConfig : ''}
  replicas: ${config.workerCount}
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform:
    aws:
      type: ${config.masterType}
  replicas: 3
metadata:
  name: ${config.clusterName}
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: OVNKubernetes
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: ${config.awsRegion}
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: ${config.deleteAfterHours}
      team: ${config.teamName}
      product: ${config.productTag}
      owner: ${config.buildUser}
      creationTime: ${config.timestamp}
pullSecret: '${config.pullSecret}'
sshKey: |
  ${config.sshPublicKey}"""

    return installConfig
}

// Overloaded method with spot instance support
def call(Map config, boolean useSpotInstances, String spotMaxPrice = '') {
    if (useSpotInstances) {
        def spotConfig = 'spotMarketOptions:\n        maxPrice: '
        if (spotMaxPrice && spotMaxPrice.trim()) {
            spotConfig += "'${spotMaxPrice}'"
        } else {
            spotConfig += "''"  // Empty string means on-demand price as max
        }
        config.spotConfig = spotConfig
    }

    return call(config)
}

// Method to validate OpenShift version
def validateVersion(String version) {
    def validVersions = ['4.16', '4.17', '4.18', '4.19']
    def majorMinor = version.tokenize('.')[0..1].join('.')

    if (!validVersions.contains(majorMinor)) {
        error "Unsupported OpenShift version: ${version}. Supported versions: ${validVersions.join(', ')}"
    }

    return true
}

// Method to validate AWS region
def validateRegion(String region) {
    def validRegions = ['us-east-1', 'us-east-2', 'us-west-1', 'us-west-2',
                        'eu-west-1', 'eu-west-2', 'eu-west-3', 'eu-central-1',
                        'ap-southeast-1', 'ap-southeast-2', 'ap-northeast-1']

    if (!validRegions.contains(region)) {
        error "Invalid AWS region: ${region}. Valid regions: ${validRegions.join(', ')}"
    }

    return true
}

// Method to validate instance type
def validateInstanceType(String instanceType) {
    // Basic validation for common instance families
    def validPatterns = [
        ~/^m[56]i?\.[a-z0-9]+$/,    // m5, m6i series
        ~/^c[56]i?\.[a-z0-9]+$/,    // c5, c6i series
        ~/^r[56]i?\.[a-z0-9]+$/,    // r5, r6i series
        ~/^t[34]\.[a-z0-9]+$/       // t3, t4 series
    ]

    def isValid = validPatterns.any { pattern ->
        instanceType.matches(pattern)
    }

    if (!isValid) {
        error "Invalid instance type: ${instanceType}. Examples of valid types: m5.xlarge, c5.2xlarge, r6i.large"
    }

    return true
}

import groovy.json.JsonBuilder

def call(Map params) {
    // Validate required parameters
    def required = ['clusterName', 'openshiftVersion', 'awsRegion', 'masterType',
                    'workerType', 'workerCount']

    required.each { param ->
        if (!params.containsKey(param) || params[param] == null || params[param].toString().trim().isEmpty()) {
            error "Missing required parameter for metadata: ${param}"
        }
    }

    // Create metadata with JsonBuilder
    def metadata = new JsonBuilder()
    metadata {
        cluster_name params.clusterName
        openshift_version params.openshiftVersion
        aws_region params.awsRegion
        created_date new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
        created_by params.buildUser ?: 'jenkins'
        jenkins_build params.buildNumber ?: '1'
        jenkins_job params.jobName ?: env.JOB_NAME ?: 'unknown'
        master_type params.masterType
        worker_type params.workerType
        worker_count params.workerCount.toString()
        test_mode params.testMode ?: false
        base_domain params.baseDomain ?: 'cd.percona.com'
        delete_after_hours params.deleteAfterHours ?: '8'
        team params.teamName ?: 'cloud'
        product params.productTag ?: 'openshift'
        spot_instances params.useSpotInstances ?: false
        if (params.spotMaxPrice) {
            spot_max_price params.spotMaxPrice
        }
        if (params.pmmDeployed != null) {
            pmm_deployed params.pmmDeployed
            if (params.pmmDeployed && params.pmmVersion) {
                pmm_version params.pmmVersion
            }
        }
        if (params.additionalMetadata) {
            params.additionalMetadata.each { key, value ->
                "$key" value
            }
        }
    }

    return metadata.toPrettyString()
}

// Overloaded method to save metadata to file
def call(Map params, String filePath) {
    def metadataJson = call(params)

    writeFile file: filePath, text: metadataJson
    echo "Metadata saved to: ${filePath}"

    return metadataJson
}

// Method to merge metadata with existing data
def merge(String existingJson, Map newParams) {
    def slurper = new groovy.json.JsonSlurper()
    def existing = slurper.parseText(existingJson)

    // Merge new params with existing, new params take precedence
    def merged = existing + newParams

    // Update modification timestamp
    merged.last_modified = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))

    return call(merged)
}

// Method to generate metadata for destroy operation
def forDestroy(Map params) {
    def destroyParams = params.clone()
    destroyParams.destroyed_date = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
    destroyParams.destroyed_by = params.destroyedBy ?: params.buildUser ?: 'jenkins'
    destroyParams.destroy_reason = params.destroyReason ?: 'scheduled'

    return call(destroyParams)
}

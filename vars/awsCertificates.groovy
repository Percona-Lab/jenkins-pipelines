/**
 * AWS Certificate Manager and Route53 integration library for SSL automation.
 *
 * This library provides AWS ACM certificate management and Route53 DNS automation
 * for OpenShift clusters and services running on AWS infrastructure.
 *
 * @since 1.0.0
 */

/**
 * Finds an existing ACM certificate matching the specified domain.
 *
 * Searches for valid (ISSUED) certificates that match the exact domain
 * or a wildcard certificate that covers the domain.
 *
 * @param config Map containing:
 *   - domain: Domain name to match (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - profile: AWS profile (optional, default: 'percona-dev-admin')
 *   - includeWildcard: Also search for wildcard certificates (optional, default: true)
 *
 * @return Certificate ARN if found, null otherwise
 */
def findACMCertificate(Map config = [:]) {
    def params = [
        region: 'us-east-2',
        profile: 'percona-dev-admin',
        includeWildcard: true
    ] + config

    if (!params.domain) {
        error 'Missing required parameter: domain'
    }

    openshiftTools.log('INFO', "Searching for ACM certificate for domain: ${params.domain}")

    try {
        // Build query for wildcard domain if applicable
        def wildcardDomain = ''
        if (params.includeWildcard && params.domain.contains('.')) {
            wildcardDomain = '*.${params.domain.substring(params.domain.indexOf('.') + 1)}'
        }

        // Search for certificates
        def query = wildcardDomain ? 
            "CertificateSummaryList[?Status=='ISSUED' && (DomainName=='${params.domain}' || DomainName=='${wildcardDomain}')].CertificateArn" :
            "CertificateSummaryList[?Status=='ISSUED' && DomainName=='${params.domain}'].CertificateArn"

        def certificateArn = sh(
            script: """
                aws acm list-certificates \\
                    --region ${params.region} \\
                    --profile ${params.profile} \\
                    --query "${query}" \\
                    --output text 2>/dev/null | head -1
            """,
            returnStdout: true
        ).trim()

        if (certificateArn && certificateArn != 'None') {
            openshiftTools.log('INFO', "Found ACM certificate: ${certificateArn}")
            
            // Verify certificate details
            def certDetails = sh(
                script: """
                    aws acm describe-certificate \\
                        --certificate-arn ${certificateArn} \\
                        --region ${params.region} \\
                        --profile ${params.profile} \\
                        --query 'Certificate.{Status:Status,DomainName:DomainName,SubjectAlternativeNames:SubjectAlternativeNames}' \\
                        --output json
                """,
                returnStdout: true
            ).trim()
            
            openshiftTools.log('DEBUG', "Certificate details: ${certDetails}")
            return certificateArn
        } else {
            openshiftTools.log('WARN', "No ACM certificate found for domain: ${params.domain}")
            return null
        }
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to find ACM certificate: ${e.message}")
        return null
    }
}

/**
 * Applies an ACM certificate to a Kubernetes LoadBalancer service.
 *
 * Configures the service annotations to use the specified ACM certificate
 * for SSL/TLS termination at the AWS Load Balancer level.
 *
 * @param config Map containing:
 *   - namespace: Kubernetes namespace (required)
 *   - serviceName: Service name (required)
 *   - certificateArn: ACM certificate ARN (required)
 *   - kubeconfig: Path to kubeconfig file (required)
 *   - backendProtocol: Backend protocol (optional, default: 'https')
 *   - sslPorts: SSL ports (optional, default: '443')
 *
 * @return Boolean indicating success
 */
def applyACMToLoadBalancer(Map config) {
    def params = [
        backendProtocol: 'https',
        sslPorts: '443'
    ] + config

    def required = ['namespace', 'serviceName', 'certificateArn', 'kubeconfig']
    required.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    env.KUBECONFIG = params.kubeconfig
    openshiftTools.log('INFO', "Applying ACM certificate to service ${params.serviceName} in namespace ${params.namespace}")

    try {
        // Create patch file with ACM annotations
        def patchFile = "/tmp/acm-patch-${BUILD_NUMBER}.yaml"
        writeFile file: patchFile, text: """
metadata:
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-backend-protocol: "${params.backendProtocol}"
    service.beta.kubernetes.io/aws-load-balancer-ssl-cert: "${params.certificateArn}"
    service.beta.kubernetes.io/aws-load-balancer-ssl-ports: "${params.sslPorts}"
"""

        // Apply patch to service
        sh """
            oc patch service ${params.serviceName} \\
                -n ${params.namespace} \\
                --patch-file ${patchFile}
        """

        // Cleanup patch file
        sh "rm -f ${patchFile}"

        // Wait for LoadBalancer to update
        openshiftTools.log('INFO', 'Waiting for LoadBalancer to update with certificate...')
        sleep(time: 30, unit: 'SECONDS')

        // Verify LoadBalancer has the certificate annotation
        def annotations = sh(
            script: """
                oc get service ${params.serviceName} -n ${params.namespace} \\
                    -o jsonpath='{.metadata.annotations.service\\.beta\\.kubernetes\\.io/aws-load-balancer-ssl-cert}'
            """,
            returnStdout: true
        ).trim()

        if (annotations == params.certificateArn) {
            openshiftTools.log('INFO', 'ACM certificate applied successfully')
            return true
        } else {
            openshiftTools.log('WARN', 'Certificate annotation not found after patch')
            return false
        }
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to apply ACM certificate: ${e.message}")
        return false
    }
}

/**
 * Creates or updates a Route53 DNS record.
 *
 * @param config Map containing:
 *   - domain: Domain name for the record (required)
 *   - value: Record value (CNAME target or A record IP) (required)
 *   - hostedZoneId: Route53 hosted zone ID (optional, will auto-detect if not provided)
 *   - recordType: DNS record type (optional, default: 'CNAME')
 *   - ttl: Time to live in seconds (optional, default: 300)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - profile: AWS profile (optional, default: 'percona-dev-admin')
 *   - action: Change action (optional, default: 'UPSERT')
 *
 * @return Boolean indicating success
 */
def createRoute53Record(Map config) {
    def params = [
        recordType: 'CNAME',
        ttl: 300,
        region: 'us-east-2',
        profile: 'percona-dev-admin',
        action: 'UPSERT'
    ] + config

    def required = ['domain', 'value']
    required.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    openshiftTools.log('INFO', "Creating Route53 ${params.recordType} record for ${params.domain}")

    try {
        // Auto-detect hosted zone if not provided
        if (!params.hostedZoneId) {
            params.hostedZoneId = getHostedZoneId([
                domain: params.domain,
                region: params.region,
                profile: params.profile
            ])
            
            if (!params.hostedZoneId) {
                error "Could not find hosted zone for domain ${params.domain}"
            }
        }

        // Create change batch JSON
        def changeBatch = """
{
  "Changes": [{
    "Action": "${params.action}",
    "ResourceRecordSet": {
      "Name": "${params.domain}",
      "Type": "${params.recordType}",
      "TTL": ${params.ttl},
      "ResourceRecords": [{
        "Value": "${params.value}"
      }]
    }
  }]
}
"""

        // Apply Route53 change
        def changeId = sh(
            script: """
                aws route53 change-resource-record-sets \\
                    --hosted-zone-id ${params.hostedZoneId} \\
                    --change-batch '${changeBatch}' \\
                    --profile ${params.profile} \\
                    --region ${params.region} \\
                    --query 'ChangeInfo.Id' \\
                    --output text
            """,
            returnStdout: true
        ).trim()

        if (changeId) {
            openshiftTools.log('INFO', "Route53 record created/updated successfully. Change ID: ${changeId}")
            
            // Wait for change to propagate
            openshiftTools.log('INFO', 'Waiting for DNS change to propagate...')
            sh """
                aws route53 wait resource-record-sets-changed \\
                    --id ${changeId} \\
                    --profile ${params.profile} \\
                    --region ${params.region} || true
            """
            
            return true
        } else {
            openshiftTools.log('WARN', 'No change ID returned from Route53')
            return false
        }
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to create Route53 record: ${e.message}")
        return false
    }
}

/**
 * Gets the Route53 hosted zone ID for a domain.
 *
 * @param config Map containing:
 *   - domain: Domain name (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - profile: AWS profile (optional, default: 'percona-dev-admin')
 *
 * @return Hosted zone ID if found, null otherwise
 */
def getHostedZoneId(Map config = [:]) {
    def params = [
        region: 'us-east-2',
        profile: 'percona-dev-admin'
    ] + config

    if (!params.domain) {
        error 'Missing required parameter: domain'
    }

    openshiftTools.log('DEBUG', "Looking for hosted zone for domain: ${params.domain}")

    try {
        // Extract base domain (e.g., 'cd.percona.com' from 'console-test.cd.percona.com')
        def domainParts = params.domain.split('\\.')
        def possibleZones = []
        
        // Build list of possible zones from most specific to least specific
        for (int i = 0; i < domainParts.size() - 1; i++) {
            possibleZones.add(domainParts[i..-1].join('.'))
        }

        openshiftTools.log('DEBUG', "Checking possible zones: ${possibleZones}")

        for (zone in possibleZones) {
            def zoneId = sh(
                script: """
                    aws route53 list-hosted-zones-by-name \\
                        --profile ${params.profile} \\
                        --region ${params.region} \\
                        --query "HostedZones[?Name=='${zone}.'].Id" \\
                        --output text 2>/dev/null | head -1
                """,
                returnStdout: true
            ).trim()

            if (zoneId && zoneId != 'None') {
                // Extract just the ID part (remove '/hostedzone/' prefix)
                zoneId = zoneId.replaceAll('/hostedzone/', '')
                openshiftTools.log('INFO', "Found hosted zone ${zone} with ID: ${zoneId}")
                return zoneId
            }
        }

        openshiftTools.log('WARN', "No hosted zone found for domain: ${params.domain}")
        return null
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to get hosted zone ID: ${e.message}")
        return null
    }
}

/**
 * Requests a new ACM certificate with DNS validation.
 *
 * Creates a new ACM certificate request and automatically creates the
 * required DNS validation records in Route53.
 *
 * @param config Map containing:
 *   - domain: Domain name for certificate (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - profile: AWS profile (optional, default: 'percona-dev-admin')
 *   - alternativeNames: Additional domain names (optional)
 *   - waitForValidation: Wait for certificate to be validated (optional, default: true)
 *
 * @return Certificate ARN if successful, null otherwise
 */
def requestACMCertificate(Map config = [:]) {
    def params = [
        region: 'us-east-2',
        profile: 'percona-dev-admin',
        waitForValidation: true,
        alternativeNames: []
    ] + config
    
    if (!params.domain) {
        error 'Missing required parameter: domain'
    }
    
    openshiftTools.log('INFO', "Requesting ACM certificate for domain: ${params.domain}")
    
    try {
        // Build AWS CLI command for certificate request
        def cmdArgs = [
            "aws acm request-certificate",
            "--domain-name ${params.domain}",
            "--validation-method DNS",
            "--region ${params.region}",
            "--profile ${params.profile}",
            "--query 'CertificateArn'",
            "--output text"
        ]
        
        // Add alternative names if provided
        if (params.alternativeNames && params.alternativeNames.size() > 0) {
            cmdArgs.add(2, "--subject-alternative-names ${params.alternativeNames.join(' ')}")
        }
        
        def certificateArn = sh(
            script: cmdArgs.join(' \\\n    '),
            returnStdout: true
        ).trim()
        
        if (!certificateArn || certificateArn == 'None') {
            error 'Failed to request ACM certificate'
        }
        
        openshiftTools.log('INFO', "Certificate requested: ${certificateArn}")
        
        // Wait for validation details to be available
        sleep(time: 10, unit: 'SECONDS')
        
        // Get DNS validation records using AWS CLI
        def validationRecords = sh(
            script: """
                aws acm describe-certificate \\
                    --certificate-arn ${certificateArn} \\
                    --region ${params.region} \\
                    --profile ${params.profile} \\
                    --query 'Certificate.DomainValidationOptions[*].[DomainName,ResourceRecord.Name,ResourceRecord.Value]' \\
                    --output json
            """,
            returnStdout: true
        ).trim()
        
        def records = readJSON(text: validationRecords)
        
        // Create DNS validation records in Route53
        records.each { record ->
            def domain = record[0]
            def recordName = record[1]
            def recordValue = record[2]
            
            if (recordName && recordValue) {
                openshiftTools.log('INFO', "Creating validation record for ${domain}: ${recordName}")
                
                // Find the hosted zone for this domain
                def baseDomain = domain.startsWith('*.') ? domain.substring(2) : domain
                def hostedZoneId = getHostedZoneId([domain: baseDomain, region: params.region, profile: params.profile])
                
                if (hostedZoneId) {
                    // Create the validation CNAME record using AWS CLI
                    def changeFile = "/tmp/acm-validation-${BUILD_NUMBER}-${System.currentTimeMillis()}.json"
                    writeFile file: changeFile, text: """
{
    "Changes": [{
        "Action": "UPSERT",
        "ResourceRecordSet": {
            "Name": "${recordName}",
            "Type": "CNAME",
            "TTL": 300,
            "ResourceRecords": [{
                "Value": "${recordValue}"
            }]
        }
    }]
}
"""
                    
                    sh """
                        aws route53 change-resource-record-sets \\
                            --hosted-zone-id ${hostedZoneId} \\
                            --change-batch file://${changeFile} \\
                            --region ${params.region} \\
                            --profile ${params.profile} \\
                            --output json > /dev/null
                        
                        rm -f ${changeFile}
                    """
                    
                    openshiftTools.log('INFO', "Created validation record: ${recordName} -> ${recordValue}")
                } else {
                    openshiftTools.log('WARN', "Could not find hosted zone for domain: ${baseDomain}")
                }
            }
        }
        
        if (params.waitForValidation) {
            openshiftTools.log('INFO', 'Waiting for certificate validation...')
            
            // Wait for certificate to be validated (up to 10 minutes)
            def maxAttempts = 20
            def attempt = 0
            def validated = false
            
            while (attempt < maxAttempts && !validated) {
                sleep(time: 30, unit: 'SECONDS')
                
                // Check certificate status using AWS CLI
                def status = sh(
                    script: """
                        aws acm describe-certificate \\
                            --certificate-arn ${certificateArn} \\
                            --region ${params.region} \\
                            --profile ${params.profile} \\
                            --query 'Certificate.Status' \\
                            --output text
                    """,
                    returnStdout: true
                ).trim()
                
                if (status == 'ISSUED') {
                    validated = true
                    openshiftTools.log('INFO', 'Certificate validated successfully!')
                } else {
                    attempt++
                    openshiftTools.log('INFO', "Certificate status: ${status}, waiting... (${attempt}/${maxAttempts})")
                }
            }
            
            if (!validated) {
                openshiftTools.log('WARN', 'Certificate validation timed out. It may still validate in the background.')
            }
        }
        
        return certificateArn
        
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to request ACM certificate: ${e.message}")
        return null
    }
}

/**
 * Validates domain ownership in AWS ACM.
 *
 * Checks if a domain has a valid certificate in ACM and if DNS validation is complete.
 *
 * @param config Map containing:
 *   - certificateArn: ACM certificate ARN (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - profile: AWS profile (optional, default: 'percona-dev-admin')
 *
 * @return Map containing validation status and details
 */
def validateDomain(Map config) {
    def params = [
        region: 'us-east-2',
        profile: 'percona-dev-admin'
    ] + config

    if (!params.certificateArn) {
        error 'Missing required parameter: certificateArn'
    }

    openshiftTools.log('INFO', "Validating ACM certificate: ${params.certificateArn}")

    try {
        def certInfo = sh(
            script: """
                aws acm describe-certificate \\
                    --certificate-arn ${params.certificateArn} \\
                    --region ${params.region} \\
                    --profile ${params.profile} \\
                    --query 'Certificate.{Status:Status,ValidationStatus:DomainValidationOptions[0].ValidationStatus,Domain:DomainName,Type:Type,InUseBy:InUseBy}' \\
                    --output json
            """,
            returnStdout: true
        ).trim()

        def certData = readJSON text: certInfo
        
        def isValid = certData.Status == 'ISSUED' && 
                     (certData.ValidationStatus == 'SUCCESS' || certData.ValidationStatus == null)

        openshiftTools.log('INFO', "Certificate validation status: ${certData.Status}")

        return [
            valid: isValid,
            status: certData.Status,
            validationStatus: certData.ValidationStatus,
            domain: certData.Domain,
            type: certData.Type,
            inUseBy: certData.InUseBy ?: []
        ]
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to validate domain: ${e.message}")
        return [
            valid: false,
            error: e.message
        ]
    }
}

/**
 * Sets up complete AWS ACM SSL configuration for services.
 *
 * This is a high-level function that orchestrates the entire ACM setup process
 * for LoadBalancer services including DNS configuration.
 *
 * @param config Map containing:
 *   - clusterName: Name of the OpenShift cluster (required)
 *   - baseDomain: Base domain (required)
 *   - kubeconfig: Path to kubeconfig file (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - profile: AWS profile (optional, default: 'percona-dev-admin')
 *   - services: List of services to configure (optional)
 *
 * @return Map containing setup results
 */
def setupACM(Map config) {
    def params = [
        region: 'us-east-2',
        profile: 'percona-dev-admin',
        services: []
    ] + config

    def required = ['clusterName', 'baseDomain', 'kubeconfig']
    required.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    openshiftTools.log('INFO', "Setting up AWS ACM SSL for cluster ${params.clusterName}")

    def results = [
        wildcardCert: null,
        services: [:],
        dns: [:],
        errors: []
    ]

    try {
        // Step 1: Find or request wildcard certificate for the base domain
        def wildcardDomain = "*.${params.baseDomain}"
        results.wildcardCert = findACMCertificate([
            domain: wildcardDomain,
            region: params.region,
            profile: params.profile
        ])

        if (!results.wildcardCert && params.autoCreateCertificate != false) {
            openshiftTools.log('INFO', "No wildcard certificate found for ${wildcardDomain}, requesting new certificate...")
            
            // Request a new wildcard certificate with DNS validation
            results.wildcardCert = requestACMCertificate([
                domain: wildcardDomain,
                region: params.region,
                profile: params.profile,
                waitForValidation: true
            ])
            
            if (results.wildcardCert) {
                openshiftTools.log('INFO', "Successfully created wildcard certificate: ${results.wildcardCert}")
            } else {
                results.errors.add("Failed to create wildcard certificate for ${wildcardDomain}")
                openshiftTools.log('WARN', "Could not create wildcard ACM certificate. Services will need individual certificates.")
            }
        } else if (!results.wildcardCert) {
            results.errors.add("No wildcard certificate found for ${wildcardDomain}")
            openshiftTools.log('WARN', "No wildcard ACM certificate found. Services will need individual certificates.")
        }

        // Step 2: Process each service
        params.services.each { service ->
            try {
                def serviceResult = [
                    configured: false,
                    certificateArn: null,
                    domain: null,
                    loadBalancer: null
                ]

                // Use wildcard cert or find/request specific cert
                serviceResult.certificateArn = results.wildcardCert
                
                if (!serviceResult.certificateArn && service.domain) {
                    // Try to find existing certificate for specific domain
                    serviceResult.certificateArn = findACMCertificate([
                        domain: service.domain,
                        region: params.region,
                        profile: params.profile
                    ])
                    
                    // Request new certificate if not found and auto-create is enabled
                    if (!serviceResult.certificateArn && params.autoCreateCertificate != false) {
                        openshiftTools.log('INFO', "Requesting certificate for ${service.domain}...")
                        serviceResult.certificateArn = requestACMCertificate([
                            domain: service.domain,
                            region: params.region,
                            profile: params.profile,
                            waitForValidation: true
                        ])
                    }
                }

                if (serviceResult.certificateArn) {
                    // Apply certificate to LoadBalancer
                    def applied = applyACMToLoadBalancer([
                        namespace: service.namespace,
                        serviceName: service.name,
                        certificateArn: serviceResult.certificateArn,
                        kubeconfig: params.kubeconfig
                    ])

                    if (applied) {
                        // Get LoadBalancer hostname
                        serviceResult.loadBalancer = sh(
                            script: """
                                export KUBECONFIG=${params.kubeconfig}
                                oc get svc ${service.name} -n ${service.namespace} \\
                                    -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
                            """,
                            returnStdout: true
                        ).trim()

                        if (serviceResult.loadBalancer && service.domain) {
                            // Create DNS record
                            def dnsCreated = createRoute53Record([
                                domain: service.domain,
                                value: serviceResult.loadBalancer,
                                region: params.region,
                                profile: params.profile
                            ])

                            if (dnsCreated) {
                                serviceResult.domain = service.domain
                                serviceResult.configured = true
                                results.dns[service.domain] = serviceResult.loadBalancer
                            }
                        }
                    }
                }

                results.services[service.name] = serviceResult
            } catch (Exception e) {
                results.errors.add("Failed to configure service ${service.name}: ${e.message}")
            }
        }

        openshiftTools.log('INFO', 'AWS ACM SSL setup completed')
    } catch (Exception e) {
        openshiftTools.log('ERROR', "ACM setup failed: ${e.message}")
        results.errors.add(e.message)
    }

    return results
}

return this

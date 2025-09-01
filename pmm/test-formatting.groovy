pipeline {
    agent {
        label 'docker'
    }
    
    parameters {
        choice(name: 'FORMAT_TEST', choices: ['CLEAN_TEXT', 'BR_ONLY', 'FULL_HTML', 'PLAIN_TEXT', 'MIXED_FORMAT', 'COMPLEX_HTML', 'PIPE_SEPARATED'], description: 'Select format to test')
    }
    
    stages {
        stage('Test Formatting') {
            steps {
                script {
                    echo "Testing format: ${params.FORMAT_TEST}"
                    
                    switch(params.FORMAT_TEST) {
                        case 'CLEAN_TEXT':
                            // Clean single-line format optimized for Blue Ocean (no HTML)
                            currentBuild.description = "helm-test-75 | OCP 4.16.9 | us-east-2 | Active | " +
                                "Console: https://console-openshift-console.apps.helm-test-75.cd.percona.com | " +
                                "API: https://api.helm-test-75.cd.percona.com:6443 | " +
                                "PMM: https://pmm.helm-test-75.cd.percona.com | " +
                                "PMM IP: 10.0.1.234 | " +
                                "admin/PMM123456 | " +
                                "Masters: 3×m5.xlarge | Workers: 3×m5.large | " +
                                "Auto-delete: 72h | Team: PMM"
                            break
                            
                        case 'BR_ONLY':
                            // Test with only BR tags - most likely to work in Blue Ocean
                            currentBuild.description = """Cluster: helm-test-75<br>
OpenShift: 4.16.9<br>
Region: us-east-2<br>
Status: Active<br>
<br>
Access URLs:<br>
• Console: https://console-openshift-console.apps.helm-test-75.cd.percona.com<br>
• API: https://api.helm-test-75.cd.percona.com:6443<br>
<br>
Login Command:<br>
oc login https://api.helm-test-75.cd.percona.com:6443 -u kubeadmin -p &lt;password&gt;<br>
Password in Jenkins artifacts<br>
<br>
PMM Monitoring:<br>
• URL: https://pmm.helm-test-75.cd.percona.com<br>
• IP: 10.0.1.234<br>
• User: admin<br>
• Password: PMM123456<br>
• Version: 2.44.0<br>
<br>
Resources:<br>
• Masters: 3 × m5.xlarge<br>
• Workers: 3 × m5.large<br>
<br>
Lifecycle:<br>
• Auto-delete: 72 hours<br>
• Team: PMM<br>
• S3 Backup: s3://openshift-clusters-119175775298-us-east-2/helm-test-75/"""
                            break
                            
                        case 'FULL_HTML':
                            // Full HTML with all tags - current approach
                            currentBuild.description = """<b>Cluster:</b> <a href='https://console-openshift-console.apps.helm-test-75.cd.percona.com'>helm-test-75</a><br/>
<b>OpenShift:</b> 4.16.9<br/>
<b>Region:</b> us-east-2<br/>
<b>Status:</b> <span style='color:green'>Active ✓</span><br/>
<br/>
<b>Access URLs:</b><br/>
• Console: <a href='https://console-openshift-console.apps.helm-test-75.cd.percona.com'>https://console-openshift-console.apps.helm-test-75.cd.percona.com</a><br/>
• API: <code>https://api.helm-test-75.cd.percona.com:6443</code><br/>
<br/>
<b>Login Command:</b><br/>
<code>oc login https://api.helm-test-75.cd.percona.com:6443 -u kubeadmin -p &lt;password&gt;</code><br/>
<i>(Password in Jenkins artifacts)</i><br/>
<br/>
<b>PMM Monitoring:</b><br/>
• URL: <a href='https://pmm.helm-test-75.cd.percona.com'>https://pmm.helm-test-75.cd.percona.com</a><br/>
• IP: <code>10.0.1.234</code><br/>
• User: <code>admin</code><br/>
• Password: <code>PMM123456</code><br/>
• Version: 2.44.0<br/>
<br/>
<b>Resources:</b><br/>
• Masters: 3 × m5.xlarge<br/>
• Workers: 3 × m5.large<br/>
<br/>
<b>Lifecycle:</b><br/>
• Auto-delete: 72 hours<br/>
• Team: PMM<br/>
• S3 Backup: <code>s3://openshift-clusters-119175775298-us-east-2/helm-test-75/</code>"""
                            break
                            
                        case 'PLAIN_TEXT':
                            // Plain text with newlines (no HTML)
                            currentBuild.description = """Cluster: helm-test-75
OpenShift: 4.16.9
Region: us-east-2
Status: Active

Access URLs:
• Console: https://console-openshift-console.apps.helm-test-75.cd.percona.com
• API: https://api.helm-test-75.cd.percona.com:6443

Login Command:
oc login https://api.helm-test-75.cd.percona.com:6443 -u kubeadmin -p <password>
Password in Jenkins artifacts

PMM Monitoring:
• URL: https://pmm.helm-test-75.cd.percona.com
• IP: 10.0.1.234
• User: admin
• Password: PMM123456
• Version: 2.44.0

Resources:
• Masters: 3 × m5.xlarge
• Workers: 3 × m5.large

Lifecycle:
• Auto-delete: 72 hours
• Team: PMM
• S3 Backup: s3://openshift-clusters-119175775298-us-east-2/helm-test-75/"""
                            break
                            
                        case 'MIXED_FORMAT':
                            // Mixed approach - minimal HTML
                            currentBuild.description = """<b>Cluster:</b> helm-test-75<br>
<b>OpenShift:</b> 4.16.9<br>
<b>Region:</b> us-east-2<br>
<b>Status:</b> Active<br>
<br>
<b>Access URLs:</b><br>
Console: https://console-openshift-console.apps.helm-test-75.cd.percona.com<br>
API: https://api.helm-test-75.cd.percona.com:6443<br>
<br>
<b>Login:</b> oc login https://api.helm-test-75.cd.percona.com:6443 -u kubeadmin<br>
<br>
<b>PMM:</b> https://pmm.helm-test-75.cd.percona.com<br>
IP: 10.0.1.234 | User: admin | Pass: PMM123456<br>
<br>
<b>Resources:</b> Masters: 3×m5.xlarge | Workers: 3×m5.large<br>
<b>Lifecycle:</b> Auto-delete: 72h | Team: PMM"""
                            break
                            
                        case 'COMPLEX_HTML':
                            // Complex HTML structure (like from screenshot)
                            def descriptionHtml = new StringBuilder()
                            descriptionHtml.append("<b>Cluster:</b> helm-test-75<br/>")
                            descriptionHtml.append("<b>OpenShift:</b> 4.16.9<br/>")
                            descriptionHtml.append("<b>Region:</b> us-east-2<br/>")
                            descriptionHtml.append("<b>Status:</b> Active<br/>")
                            descriptionHtml.append("<b>Access URLs:</b><br/>")
                            descriptionHtml.append("• Console: <a href='https://console-openshift-console.apps.helm-test-75.cd.percona.com'>https://console-openshift-console.apps.helm-test-75.cd.percona.com</a><br/>")
                            descriptionHtml.append("• API: <code>https://api.helm-test-75.cd.percona.com:6443</code><br/>")
                            descriptionHtml.append("<b>Login Command:</b><br/>")
                            descriptionHtml.append("<code>oc login https://api.helm-test-75.cd.percona.com:6443 -u kubeadmin -p &lt;password&gt;</code><br/>")
                            descriptionHtml.append("<i>(Password in Jenkins artifacts)</i><br/>")
                            descriptionHtml.append("<b>Resources:</b><br/>")
                            descriptionHtml.append("• Masters: 3 × m5.xlarge<br/>")
                            descriptionHtml.append("• Workers: 3 × m5.large<br/>")
                            descriptionHtml.append("<b>Lifecycle:</b><br/>")
                            descriptionHtml.append("• Auto-delete: 72 hours<br/>")
                            descriptionHtml.append("• Team: PMM<br/>")
                            descriptionHtml.append("• S3 Backup: <code>s3://openshift-clusters-119175775298-us-east-2/helm-test-75/</code><br/>")
                            
                            currentBuild.description = descriptionHtml.toString()
                            break
                            
                        case 'PIPE_SEPARATED':
                            // Pipe-separated format for Blue Ocean
                            currentBuild.description = "helm-test-75 | OCP 4.16.9 | us-east-2 | Active | " +
                                "Console: https://console-openshift-console.apps.helm-test-75.cd.percona.com | " +
                                "API: https://api.helm-test-75.cd.percona.com:6443 | " +
                                "PMM: https://pmm.helm-test-75.cd.percona.com | " +
                                "Masters: 3×m5.xlarge | Workers: 3×m5.large | " +
                                "Auto-delete: 72h | Team: PMM"
                            break
                    }
                    
                    echo "Description set. Check Blue Ocean and Classic UI to compare rendering."
                    echo ""
                    echo "Blue Ocean URL: ${env.JENKINS_URL}/blue/organizations/jenkins/test-blue-ocean-formatting/"
                    echo ""
                    echo "Classic UI: ${env.JENKINS_URL}/job/test-blue-ocean-formatting/"
                    echo ""
                    echo "Cluster Information:"
                    echo "  Name: helm-test-75"
                    echo "  OpenShift: 4.16.9"
                    echo "  Region: us-east-2"
                    echo "  Status: Active"
                    echo ""
                    echo "Access URLs:"
                    echo "  Console: https://console-openshift-console.apps.helm-test-75.cd.percona.com"
                    echo "  API: https://api.helm-test-75.cd.percona.com:6443"
                    echo ""
                    echo "Login Command:"
                    echo "  oc login https://api.helm-test-75.cd.percona.com:6443 -u kubeadmin -p <password>"
                    echo ""
                    echo "PMM Details:"
                    echo "  URL: https://pmm.helm-test-75.cd.percona.com"
                    echo "  Public IP: 10.0.1.234"
                    echo "  Credentials: admin/PMM123456"
                    echo "  Version: 2.44.0"
                    echo ""
                    echo "Resources:"
                    echo "  Masters: 3 × m5.xlarge"
                    echo "  Workers: 3 × m5.large"
                    echo ""
                    echo "Lifecycle:"
                    echo "  Auto-delete: 72 hours"
                    echo "  Team: PMM"
                    echo "  S3 Backup: s3://openshift-clusters-119175775298-us-east-2/helm-test-75/"
                }
            }
        }
    }
}
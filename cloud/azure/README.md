1. In the Azure portal, search for **Function App** (make sure the subscription is set to **eng-cloud-dev**).
   In the list, find the Function App named **DeleteOrpanedK8sResources**.

2. Open this Function App and select the function **aks-cleanup-function**.

3. To update this function, modify the code **locally** and then **redeploy** it to Azure.

# To redeploy function run in jenkins-pipelines/cloud/azure/cmd folder:
``
zip -r ../aks-cleanup.zip . -x "local.settings.json" ".funcignore" "**/__pycache__/*" ".git/*" ".venv/*"

az functionapp deployment source config-zip --resource-group percona-operators --name DeleteOrpanedK8sResources --src  ../aks-cleanup.zip

``

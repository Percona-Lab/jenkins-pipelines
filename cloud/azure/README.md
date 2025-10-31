# To redeploy function run in cmd folder: 
``
zip -r ../aks-cleanup.zip . -x "local.settings.json" ".funcignore" "**/__pycache__/*" ".git/*" ".venv/*"

az functionapp deployment source config-zip --resource-group percona-operators --name DeleteOrpanedK8sResources --src  ../aks-cleanup.zip

``

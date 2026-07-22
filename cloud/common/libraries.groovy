def loadLibraries() {
    return [
        azure       : load('cloud/common/vars/azure.groovy'),
        dependencies: load('cloud/common/vars/dependencies.groovy'),
        tools       : load('cloud/common/vars/tools.groovy'),
        gcloud      : load('cloud/common/vars/gcloud.groovy'),
        minikube    : load('cloud/common/vars/minikube.groovy'),
        rancher     : load('cloud/common/vars/rancher.groovy'),
        tests       : load('cloud/common/vars/tests.groovy')
    ]
}

return this

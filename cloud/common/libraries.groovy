def loadLibraries() {
    return [
        aks         : load('cloud/common/vars/aks.groovy'),
        credentials : load('cloud/common/vars/credentials.groovy'),
        dependencies: load('cloud/common/vars/dependencies.groovy'),
        helpers     : load('cloud/common/vars/helpers.groovy'),
        tools       : load('cloud/common/vars/tools.groovy'),
        gke         : load('cloud/common/vars/gke.groovy'),
        rancher     : load('cloud/common/vars/rancher.groovy'),
        eks         : load('cloud/common/vars/eks.groovy'),
        doks        : load('cloud/common/vars/doks.groovy'),
        openshift   : load('cloud/common/vars/openshift.groovy'),
        minikube    : load('cloud/common/vars/minikube.groovy'),
        tests       : load('cloud/common/vars/tests.groovy')
    ]
}

return this

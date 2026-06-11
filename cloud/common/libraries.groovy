def loadLibraries() {
    return [
        azure       : load('cloud/common/vars/azure.groovy'),
        dependencies: load('cloud/common/vars/dependencies.groovy'),
        tools       : load('cloud/common/vars/tools.groovy'),
        google      : load('cloud/common/vars/google.groovy'),
        rancher     : load('cloud/common/vars/rancher.groovy'),
        tests       : load('cloud/common/vars/tests.groovy')
    ]
}

return this

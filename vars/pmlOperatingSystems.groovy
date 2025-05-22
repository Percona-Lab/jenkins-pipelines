def call(String version = 'default', String newVersion = null, String gatedBuild = 'false') {
    Boolean isGatedBuild = gatedBuild.toLowerCase() == 'true'

    def switchValues = [
            ("pml"): ['debian-11', 'debian-12', 'ubuntu-focal', 'rhel8', 'rhel9', 'ubuntu-jammy', 'ubuntu-noble', 'rhel8-arm', 'rhel9-arm', 'ubuntu-focal-arm', 'ubuntu-jammy-arm', 'ubuntu-noble-arm', 'al2023', 'al2023-arm'],
            'default': ['centos-7', 'debian-10', 'debian-11', 'ubuntu-focal', 'rhel8', 'ubuntu-jammy'],
    ]

    def versionValues = switchValues.find { key, value -> version ==~ key }?.value ?: switchValues['default']
    def newVersionValues = switchValues.find { key, value -> newVersion ==~ key }?.value ?: versionValues

    return versionValues.intersect(newVersionValues)
}

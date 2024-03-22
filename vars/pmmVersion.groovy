def call(String type='dev-latest') {
  List<String> oldVersions = ['2.11.0', '2.12.0', '2.13.0']
  HashMap<String, String> amiVersions = [
    // Historical AMIs
    // '2.15.0': 'ami-086a3a95eefa9567f',
    // '2.15.1': 'ami-073928dbea8c7ebc3',
    // '2.16.0': 'ami-01097b383f63f7db5',
    // '2.17.0': 'ami-03af848f3557ff8d0',
    // '2.18.0': 'ami-0184c7b18b45d2a7b',
    // '2.19.0': 'ami-0d3c21da426d248d3',
    // '2.20.0': 'ami-04ad85dd7364bba21',
    // '2.21.0': 'ami-0605d9dbdc9d6a233',
    // '2.22.0': 'ami-0fb9ae57bc30787cd',
    // '2.23.0': 'ami-012c6702ff13e97d0',
    // '2.24.0': 'ami-0e688a9b5dca3b3d2',
    // '2.25.0': 'ami-09931a649be4b90e8',
    // '2.26.0': 'ami-0579b750aaa578090',
    // '2.27.0': 'ami-064970de413ee5144',
    // '2.28.0': 'ami-015cbf0312dd101c7',
    // '2.29.0': 'ami-0e68224439dd6f200',
    // '2.29.1': 'ami-01ce74cdab54cabcd',
    // '2.30.0': 'ami-0ac4cb922edec19e0',
    // '2.31.0': 'ami-04c7431377165bca7',
    // '2.32.0': 'ami-02cfe7580e77fb5fa',
    // '2.33.0': 'ami-005acacf35adcfa57',
    // Newer AMIs, us-east-1
    // '2.12.0': 'ami-0b5539a85b450ad6a',
    // '2.13.0': 'ami-082bcd79afc35d576',
    // '2.19.0': 'ami-0a8d9caa10fb1aaf3',
    // '2.20.0': 'ami-0aa5c278075c61158',
    // '2.21.0': 'ami-03a64919e4f2afe32',
    '2.22.0': 'ami-0fd1d4c619b968b18',
    '2.23.0': 'ami-0a98f83f5406c379e',
    '2.24.0': 'ami-07008195e6afe0694',
    // '2.25.0': 'ami-0b5a9dff554ea6d12',
    '2.26.0': 'ami-09914490ee52c24c6',
    '2.27.0': 'ami-0e1dd6bf6627aed8e',
    '2.28.0': 'ami-0b6ea9aa3292b41dd',
    '2.29.0': 'ami-059b09b175765a958',
    '2.29.1': 'ami-07f8ad99be4ed007b',
    '2.30.0': 'ami-0e7021d395f91de57',
    '2.31.0': 'ami-08094ef29ebc82468',
    '2.32.0': 'ami-0bd413810ebb52d5d',
    '2.33.0': 'ami-07e19e90c30c6989c',
    '2.34.0': 'ami-06dd2cfc9b70bb79b',
    '2.35.0': 'ami-003bff3e1c59f54b3',
    '2.36.0': 'ami-0ce04c507ec1187b1',
    '2.37.0': 'ami-04fc023254a0a0824',
    '2.37.1': 'ami-07858434974406b74',
    '2.38.0': 'ami-09895e9b605f14cbc',
    '2.38.1': 'ami-0c8a2742c5fef0023',
    '2.39.0': 'ami-079ca34c1b72b8e41',
    '2.40.0': 'ami-0bd8647a4f1204987',
    '2.40.1': 'ami-08cc6c7835df9916f',
    '2.41.0': 'ami-0a04085f4c721e913',
    '2.41.1': 'ami-0cd5c6eba2986ff39',
    '2.41.2': 'ami-040faf3a8c1457f16'
  ]
  List<String> versionsList = amiVersions.keySet() as List<String>;
  // Grab 5 latest versions
  List<String> ovfVersions = versionsList[-5..-1]
  List<String> dbaasVersions = versionsList[-5..-1]

  switch(type) {
    case 'dev-latest':
      def latestVersion = httpRequest "https://raw.githubusercontent.com/Percona-Lab/pmm-submodules/PMM-2.0/VERSION"
      return latestVersion.content
    case 'rc':
      def resp = httpRequest "https://registry.hub.docker.com/v2/repositories/perconalab/pmm-client/tags?page_size=25&name=rc"
      return new groovy.json.JsonSlurper().parseText(resp.content)
              .results
              .findAll { it.name.endsWith("-rc") }
              .collect { it.name }
              .sort()[-1]
              .split('-')[0]
    case 'stable':
      return versionsList[versionsList.size() - 2]
    case 'ami':
      return amiVersions
    case 'list':
      return versionsList
    case 'ovf':
      return ovfVersions
    case 'dbaas':
      return dbaasVersions
    case 'list_with_old':
      return oldVersions + versionsList
  }
}

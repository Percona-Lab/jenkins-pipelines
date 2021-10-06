def call(includeAMI=false) {
  versions = [
    '2.15.0': 'ami-086a3a95eefa9567f',
    '2.15.1': 'ami-073928dbea8c7ebc3',
    '2.16.0': 'ami-01097b383f63f7db5',
    '2.17.0': 'ami-03af848f3557ff8d0',
    '2.18.0': 'ami-0184c7b18b45d2a7b',
    '2.19.0': 'ami-0d3c21da426d248d3',
    '2.20.0': 'ami-04ad85dd7364bba21',
    '2.21.0': 'ami-0605d9dbdc9d6a233',
    '2.22.0': 'ami-0fb9ae57bc30787cd',
    '2.23.0': 'ami-0cb9f8d165624e522'
  ]
  if (includeAMI) {
    return versions
  } else {
    return versions.keySet()
  }
}

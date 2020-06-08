def call() {
  return [sshUserPrivateKey(credentialsId: 'MOLECULE_AWS_PRIVATE_KEY', keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY', passphraseVariable: '', usernameVariable: ''),
          string(credentialsId: 'GCP_SECRET_KEY', variable: 'GCP_SECRET_KEY'), string(credentialsId: 'GCP_ACCESS_KEY', variable: 'GCP_ACCESS_KEY'),
          [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '4462f2e5-f01c-4e3f-9586-2ffcf5bf366a', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]
}

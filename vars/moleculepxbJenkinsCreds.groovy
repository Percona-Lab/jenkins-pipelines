def call() {
  return [sshUserPrivateKey(credentialsId: 'MOLECULE_AWS_PRIVATE_KEY', keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY', passphraseVariable: '', usernameVariable: ''),
         [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]
}

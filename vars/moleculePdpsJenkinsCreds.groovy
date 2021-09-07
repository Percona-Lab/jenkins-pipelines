def call() {
  return [sshUserPrivateKey(credentialsId: 'MOLECULE_AWS_PRIVATE_KEY', keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY', passphraseVariable: '', usernameVariable: ''),
         [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]
}

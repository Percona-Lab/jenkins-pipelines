def call(operatingSystems, moleculeDir) {
    posts = [:]
    operatingSystems.each { os ->
     posts["${os}"] = {
        sh """
            cd ${moleculeDir}
            molecule destroy -s ${os}
        """
  }
 }
 parallel posts
}

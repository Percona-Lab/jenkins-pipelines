def call(operatingSystems, moleculeDir) {
    posts = [:]
    operatingSystems.each { os ->
     posts["${os}"] = {
        sh """
            . virtenv/bin/activate
            cd ${moleculeDir}
            molecule destroy -s ${os}
        """
  }
 }
 parallel posts
}

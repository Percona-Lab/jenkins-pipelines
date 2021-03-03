def call(String PMM_QA_GIT_BRANCH) {
  sh """
    sudo mkdir -p /srv/pmm-qa || :
    pushd /srv/pmm-qa
        sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
        sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
        sudo chmod 755 pmm-tests/install-google-chrome.sh
        bash ./pmm-tests/install-google-chrome.sh
    popd
    sudo ln -s /usr/bin/google-chrome-stable /usr/bin/chromium
  """
}

// valkeyUpload — run an upload to repo.ci.percona.com (uploadRPMfromAWS,
// uploadDEBfromAWS, uploadTarballfromAWS) again when it fails, instead of
// failing the Valkey package job.
//
// The Valkey jobs upload from every platform stage in parallel, and
// hetzner-valkey-ALL(-NEXT) runs six module jobs at once, so many ssh/scp
// connections open at the same moment. When more of them are still in the
// handshake than sshd's MaxStartups allows, sshd drops the extra ones
// ("drop connection #12 ... past MaxStartups"; the client sees
// "kex_exchange_identification: Connection closed by remote host").
//
// A rerun is safe: the upload step starts from an empty workspace, downloads
// the same stash again, and mkdir -p + scp overwrite the same files. The wait
// grows with each attempt and differs per stage, so stages that failed in the
// same burst do not all reconnect at the same time. An aborted build is not
// retried.
//
// Usage: valkeyUpload { uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH) }
def call(int attempts = 4, Closure upload) {
    for (int i = 1; i <= attempts; i++) {
        try {
            upload()
            return
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e
        } catch (Exception e) {
            String msg = e.message ?: e.toString()
            if (i == attempts) {
                echo "Upload to repo.ci.percona.com failed ${attempts} times, giving up"
                throw e
            }
            // Spread the stages by their names (each platform stage has its
            // own): no Math.random, which the sandbox would need approved.
            int waitSeconds = 30 * i + ((env.STAGE_NAME ?: '').length() * 7) % 30
            echo "Upload attempt ${i}/${attempts} to repo.ci.percona.com failed: ${msg}. Retrying in ${waitSeconds}s"
            sleep(time: waitSeconds, unit: 'SECONDS')
        }
    }
}

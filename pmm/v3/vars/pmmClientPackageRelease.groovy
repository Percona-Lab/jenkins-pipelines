/**
 * RPM/DEB release suffix for pinned pmm-client package installs.
 * Matches percona/pmm build-client-packages after PMM-7 (RPM_RELEASE/DEB_RELEASE reset to 1 for 3.8.1+).
 */
String call(String clientVersion) {
    if (!(clientVersion ==~ /^3\.\d+\.\d+$/)) {
        return null
    }
    if (clientVersion in ['3.7.1', '3.8.0']) {
        return '8'
    }
    def minor = clientVersion.tokenize('.')[1] as int
    if (clientVersion == '3.8.1' || minor > 8) {
        return '1'
    }
    return '7'
}

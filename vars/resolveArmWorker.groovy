// resolveArmWorker: pick the build-worker label, the dispatcher (micro) label, and the
// effective cloud for an ARM (or x86) build, with automatic Hetzner -> AWS Graviton
// fallback when CLOUD=auto and Hetzner ARM (CAX) capacity is unavailable (PS-11179).
//
// Drop-in for the fleet-wide ternary. The whole fleet uses one shape:
//     label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-64gb-aarch64'
//     agent { label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon' }
// and re-reads params.CLOUD downstream for credentials (HTZ_STASH vs AWS), the ccache
// S3 bucket/endpoint, etc. So this returns the EFFECTIVE cloud, not just a label:
//
//     def w = resolveArmWorker(cloud: params.CLOUD, arch: params.ARCH,
//                              awsLabel: 'docker-64gb-aarch64')   // keep the caller's size
//     // outer:  agent { label w.microLabel }
//     // inner:  node(w.label) { ... }
//     // creds/S3/ccache: use w.cloudChosen wherever params.CLOUD was read
//
// It preserves each caller's existing label pair (docker-32gb vs docker-64gb aarch64),
// so it is a no-behaviour-change drop-in for explicit Hetzner/AWS and only adds the
// auto-routing. Only `docker-aarch64` is actually requested fleet-wide (the other CAX
// aarch64 label atoms have no consumers), so no per-label mapping table is needed.
//
// Returns: [label, microLabel, cloudChosen, reason, fellBack].
//
// CLOUD contract:
//   auto      (new default) - aarch64 may fall back to AWS when the controller flag
//                             reports Hetzner arm64 down; x86 always stays on Hetzner.
//   Hetzner                 - always Hetzner; never fall back (lets infra issues surface).
//   AWS                     - always AWS.
//   epyc9654                - bare-metal AMD; unchanged.
//
// Health flag (HETZNER_ARM64_HEALTHY / _HEALTH_AT / _REASON) is published by the
// controller HetznerArmHealth PeriodicWork. Fail-safe: an absent or stale flag is
// treated as healthy, so a flag outage never strands builds (keeps current behaviour).

def call(Map config = [:]) {
    String cloud  = (config.get('cloud', env.CLOUD) ?: 'auto').toString().trim()
    String arch   = (config.get('arch', env.ARCH) ?: 'x86_64').toString().trim()
    boolean isArm = (arch == 'aarch64')

    // Caller's two label sides; defaults match the common ternary. awsLabel carries the
    // caller's sizing intent (docker-32gb-aarch64 for MTR, docker-64gb-aarch64 for others).
    String hetznerLabel = config.get('hetznerLabel', isArm ? 'docker-aarch64' : 'docker-x64')
    String awsLabel     = config.get('awsLabel', isArm ? 'docker-32gb-aarch64' : 'docker-32gb')
    String epycLabel    = config.get('epycLabel', 'as-1015cs-tnr')
    // Parse defensively: a bad caller value must not throw before the fail-safe runs.
    long flagTtlSeconds
    try { flagTtlSeconds = ((config.get('flagTtlSeconds', 600L) ?: 600L) as long) }
    catch (ignored) { flagTtlSeconds = 600L }

    Map r
    switch (cloud) {
        case 'AWS':
            r = [label: awsLabel, microLabel: 'micro-amazon', cloudChosen: 'AWS',
                 reason: 'explicit CLOUD=AWS', fellBack: false]
            break
        case 'epyc9654':
            r = [label: epycLabel, microLabel: 'launcher-x64', cloudChosen: 'epyc9654',
                 reason: 'explicit CLOUD=epyc9654', fellBack: false]
            break
        case 'Hetzner':
            r = [label: hetznerLabel, microLabel: 'launcher-x64', cloudChosen: 'Hetzner',
                 reason: 'explicit CLOUD=Hetzner (no fallback)', fellBack: false]
            break
        default: // 'auto' and any unknown value
            if (isArm && !hetznerArmHealthy(flagTtlSeconds)) {
                r = [label: awsLabel, microLabel: 'micro-amazon', cloudChosen: 'AWS', fellBack: true,
                     reason: "auto: Hetzner arm64 unavailable (${env.HETZNER_ARM64_REASON ?: 'flag=false'}) -> Graviton"]
            } else {
                r = [label: hetznerLabel, microLabel: 'launcher-x64', cloudChosen: 'Hetzner', fellBack: false,
                     reason: isArm ? 'auto: Hetzner arm64 healthy' : 'auto: x86 routes to Hetzner']
            }
            break
    }

    echo "resolveArmWorker: cloud=${cloud} arch=${arch} -> ${r.cloudChosen} " +
         "label=${r.label} micro=${r.microLabel} (${r.reason})"
    try {
        String tag = r.fellBack ? "[FALLBACK ${r.cloudChosen}:${r.label}]" : "[${r.cloudChosen}:${r.label}]"
        currentBuild.description = currentBuild.description ? "${currentBuild.description} ${tag}" : tag
    } catch (ignored) { }
    return r
}

// True if Hetzner arm64 is healthy. Fail-safe to TRUE when the flag is absent, stale, or
// unparseable, so a flag outage keeps current Hetzner behaviour rather than stranding builds.
boolean hetznerArmHealthy(long ttlSeconds) {
    String healthy = env.HETZNER_ARM64_HEALTHY
    String at      = env.HETZNER_ARM64_HEALTH_AT
    if (healthy == null || at == null) {
        echo 'resolveArmWorker: HETZNER_ARM64 flag absent; assuming healthy (fail-safe)'
        return true
    }
    long ageSec
    try {
        ageSec = (long) (System.currentTimeMillis() / 1000L) - (at as long)
    } catch (ignored) {
        echo 'resolveArmWorker: HETZNER_ARM64_HEALTH_AT unparseable; assuming healthy (fail-safe)'
        return true
    }
    if (ageSec < 0L) {
        echo 'resolveArmWorker: HETZNER_ARM64_HEALTH_AT in the future (clock skew/corrupt); assuming healthy (fail-safe)'
        return true
    }
    if (ageSec > ttlSeconds) {
        echo "resolveArmWorker: HETZNER_ARM64 flag stale (${ageSec}s > ${ttlSeconds}s); assuming healthy (fail-safe)"
        return true
    }
    return !healthy.equalsIgnoreCase('false')
}

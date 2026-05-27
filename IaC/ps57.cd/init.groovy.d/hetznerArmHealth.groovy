// hetznerArmHealth.groovy  (PS-11179)
//
// Master-side health probe that publishes whether Hetzner arm64 (CAX) capacity is
// currently usable, as Jenkins GLOBAL env vars consumed by vars/resolveArmWorker:
//
//     HETZNER_ARM64_HEALTHY    "true" | "false"
//     HETZNER_ARM64_HEALTH_AT  epoch seconds of the last SUCCESSFUL observation
//     HETZNER_ARM64_REASON     short human-readable verdict
//
// Runs ON THIS CONTROLLER (not a worker). Every 60s on the Jenkins Timer pool it
// reads this master's OWN loopback metrics endpoint
// (http://127.0.0.1:8080/hetzner-prometheus) and inspects
// hetzner_dc_circuit_breaker_state{arch="arm64"} for every arm64 DC.
//
// Verdict:
//   - UNHEALTHY when every arm64 DC breaker is non-CLOSED (state >= 1, i.e. OPEN or
//     HALF_OPEN): no German DC can currently fulfil an arm64 server.
//   - HEALTHY otherwise (at least one arm64 DC breaker CLOSED, or no arm64 series yet).
// It reads the metrics TEXT only; it never calls DcCircuitBreaker.getState() from
// Groovy (that would mutate breaker state / arm a HALF_OPEN probe lease).
//
// Hysteresis: flip to UNHEALTHY immediately (divert away from dead capacity fast),
// but require CLEAR_POLLS consecutive healthy observations before flipping back to
// HEALTHY (avoid flapping a half-recovered DC back and forth).
//
// Fail-safe by OMISSION: on any fetch/parse error we DO NOT touch the flag, so it
// goes stale and resolveArmWorker treats stale/absent as healthy (-> Hetzner, the
// current behaviour). UNHEALTHY is published only on POSITIVE fresh evidence, so a
// metrics glitch can never force the costly AWS fallback on.
//
// In-memory by design: the env vars live in globalNodeProperties and are NOT saved
// to disk (no per-minute config churn). After a restart this script republishes
// within ~1 min; until then resolveArmWorker's stale/absent fail-safe holds.
//
// Idempotent re-deploy: re-evaluating this file (jenkins iac deploy / boot) bumps a
// generation token; the previously scheduled task sees the mismatch on its next tick
// and self-terminates, so exactly one probe converges within 60s.

import jenkins.model.Jenkins
import jenkins.util.Timer
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.logging.Logger

final Logger LOG = Logger.getLogger('hetznerArmHealth')
final String METRICS_URL = 'http://127.0.0.1:8080/hetzner-prometheus/'   // trailing slash avoids a 302
final int CLEAR_POLLS = 2          // consecutive healthy polls before re-enabling Hetzner
final long PERIOD_SEC = 60L
final long INITIAL_DELAY_SEC = 15L

// Generation token: supersede any schedule left by an earlier eval of this file.
final String GEN = UUID.randomUUID().toString()
System.setProperty('hetznerArmHealth.gen', GEN)

def parseArmBreakerStates = { String body ->
    def states = []
    body.eachLine { String line ->
        if (line.startsWith('hetzner_dc_circuit_breaker_state') && line.contains('arch="arm64"')) {
            def m = (line =~ /\}\s+([0-9.eE+-]+)\s*$/)
            if (m.find()) { states << (m.group(1) as double) }
        }
    }
    return states
}

def publishEnv = { boolean healthy, long atSec, String reason ->
    def props = Jenkins.instance.globalNodeProperties
    def envProp = props.get(hudson.slaves.EnvironmentVariablesNodeProperty)
    if (envProp == null) {
        envProp = new hudson.slaves.EnvironmentVariablesNodeProperty()
        props.add(envProp)
    }
    def vars = envProp.envVars
    vars.put('HETZNER_ARM64_HEALTHY', healthy ? 'true' : 'false')
    vars.put('HETZNER_ARM64_HEALTH_AT', atSec.toString())
    vars.put('HETZNER_ARM64_REASON', reason)
}

Runnable probe = {
    try {
        if (System.getProperty('hetznerArmHealth.gen') != GEN) {
            throw new CancellationException('superseded by newer hetznerArmHealth eval')
        }

        String body
        try {
            def conn = (java.net.HttpURLConnection) new URL(METRICS_URL).openConnection()
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            int code = conn.responseCode
            if (code != 200) {
                LOG.warning("hetznerArmHealth: metrics HTTP ${code}; leaving flag untouched (stale->healthy fail-safe)")
                return
            }
            body = conn.inputStream.getText('UTF-8')
        } catch (CancellationException ce) {
            throw ce
        } catch (Exception fe) {
            LOG.warning("hetznerArmHealth: metrics fetch failed (${fe.message}); leaving flag untouched (stale->healthy fail-safe)")
            return
        }

        def states = parseArmBreakerStates(body)
        boolean observedHealthy
        String reason
        if (states.isEmpty()) {
            observedHealthy = true
            reason = 'no arm64 DC breaker series (assuming healthy)'
        } else {
            int closed = states.count { it < 1.0d }
            observedHealthy = (closed > 0)
            reason = observedHealthy ?
                "${closed}/${states.size()} arm64 DC breakers CLOSED".toString() :
                "all ${states.size()} arm64 DC breakers non-CLOSED (OPEN/HALF_OPEN)".toString()
        }

        boolean priorHealthy = (System.getProperty('hetznerArmHealth.published', 'true') != 'false')
        int priorStreak = (System.getProperty('hetznerArmHealth.streak', '0')) as int
        boolean publishHealthy
        int streak
        if (!observedHealthy) {
            publishHealthy = false
            streak = 0
        } else {
            streak = priorStreak + 1
            publishHealthy = priorHealthy || (streak >= CLEAR_POLLS)
            if (!publishHealthy) {
                reason = "recovering (${streak}/${CLEAR_POLLS} healthy polls); holding AWS fallback".toString()
            }
        }
        System.setProperty('hetznerArmHealth.published', publishHealthy ? 'true' : 'false')
        System.setProperty('hetznerArmHealth.streak', streak.toString())

        long nowSec = (long) (System.currentTimeMillis() / 1000L)
        publishEnv(publishHealthy, nowSec, reason)
        LOG.fine("hetznerArmHealth: healthy=${publishHealthy} (${reason})")
    } catch (CancellationException supersede) {
        throw supersede   // let scheduleAtFixedRate suppress this stale generation
    } catch (Throwable t) {
        LOG.warning("hetznerArmHealth: unexpected ${t}")   // swallow so the probe keeps running
    }
}

Timer.get().scheduleAtFixedRate(probe, INITIAL_DELAY_SEC, PERIOD_SEC, TimeUnit.SECONDS)
LOG.info("hetznerArmHealth: scheduled (gen=${GEN}, every ${PERIOD_SEC}s, loopback ${METRICS_URL})")

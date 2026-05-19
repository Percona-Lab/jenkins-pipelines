/**
 * Persist the global pipeline durability hint as MAX_SURVIVABILITY at
 * every JVM boot, by calling the GlobalDefaultFlowDurabilityLevel
 * descriptor's setDurabilityHint() + save() so the value lands in
 * $JENKINS_HOME/org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel.xml
 * (the descriptor's standard config file). Idempotent: no-op when the
 * current hint already matches.
 *
 * Background. Pipeline builds use one of three durability hints:
 *   PERFORMANCE_OPTIMIZED  - FlowNode state kept in memory until the
 *                            build finishes; an abrupt JVM stop
 *                            (kill -9, AWS spot interrupt) loses the
 *                            whole in-flight build.
 *   SURVIVABLE_NONATOMIC   - persists eventually, weaker ordering.
 *   MAX_SURVIVABILITY      - persists each FlowNode as it executes, so
 *                            a build resumes at the same step on JVM
 *                            return.
 *
 * Why explicit when workflow-api already falls back to MAX_SURVIVABILITY
 * if no config file exists? Two reasons. (1) The fallback is fragile:
 * any operator change in Manage Jenkins -> System silently writes a
 * config file and can replace the hint, with no IaC trace. Writing the
 * config file ourselves anchors the canary's resilience guarantee.
 * (2) `grep -r MAX_SURVIVABILITY IaC/ps3.cd/` now confirms the
 * intended setting from source control alone.
 *
 * Scope: ps3 canary only. PS-11173 Phase 2.
 */
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint
import jenkins.model.Jenkins

def descCls = Class.forName('org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel$DescriptorImpl')
def descriptors = Jenkins.instance.getExtensionList(descCls)
if (descriptors.isEmpty()) {
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel descriptor missing (workflow-api plugin not installed?)"
    return
}

def d = descriptors[0]
def current = d.durabilityHint
def target = FlowDurabilityHint.MAX_SURVIVABILITY

if (current != target) {
    d.setDurabilityHint(target)
    d.save()
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel ${current} -> ${target}"
} else {
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel already ${target}"
}

/**
 * Persist the global pipeline durability hint as MAX_SURVIVABILITY at
 * every JVM boot, by calling the GlobalDefaultFlowDurabilityLevel
 * descriptor's setDurabilityHint() + save() so the value lands in
 * $JENKINS_HOME/org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel.xml
 * (the descriptor's standard config file). Idempotent on the happy
 * path: when the hint already matches the target, the script is a no-op.
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
 * Why explicit when workflow-api already advertises MAX_SURVIVABILITY
 * as the default? The default is fragile: any operator change in
 * Manage Jenkins -> System silently writes the descriptor's XML and
 * can replace the hint with no IaC trace. Writing the value ourselves
 * at every boot anchors the canary's resilience guarantee.
 *
 * Note on the descriptor: when no XML exists, the descriptor's in-memory
 * field is null (the MAX_SURVIVABILITY default lives at a higher
 * layer in workflow-api). So `current != target` correctly fires the
 * write path on a fresh master, and the resulting save() materialises
 * the on-disk XML.
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
    println "durability.groovy: ${current} -> ${target}"
} else {
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel already ${target}"
}

/**
 * Persist the global pipeline durability hint as MAX_SURVIVABILITY at
 * every JVM boot, by calling the GlobalDefaultFlowDurabilityLevel
 * descriptor's setDurabilityHint() + save() so the value lands in
 * $JENKINS_HOME/org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel.xml
 * (the descriptor's standard config file).
 *
 * Write conditions: the current in-memory hint differs from the target,
 * OR the descriptor's config XML is missing on disk. The second
 * condition matters because the workflow-api descriptor returns
 * MAX_SURVIVABILITY as its in-memory default when no XML exists, so a
 * naive `current == target` guard would never create the file and we
 * would never have an on-disk IaC trace.
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
 * (2) The on-disk XML is the auditable receipt that the script ran.
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
def xml = new File(Jenkins.instance.rootDir,
        "org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel.xml")

if (current != target || !xml.exists()) {
    d.setDurabilityHint(target)
    d.save()
    def why = (current != target) ? "${current} -> ${target}" : "rewriting missing xml (hint already ${target})"
    println "durability.groovy: ${why}"
} else {
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel already ${target} (xml present)"
}

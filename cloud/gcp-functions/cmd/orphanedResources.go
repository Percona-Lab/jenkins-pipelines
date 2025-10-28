package orphanedResources

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	"google.golang.org/api/compute/v1"
)

// firewallNamePatterns are substrings we consider relevant for deletion candidates.
// You can adjust these patterns to your naming conventions.
var firewallNamePatterns = []string{"gke-", "-pxc-", "-psmdb-", "-ps-", "-pg-", "-jen-", "k8s-"}

func matchesFirewallPattern(name string) bool {
	for _, p := range firewallNamePatterns {
		if strings.Contains(name, p) {
			return true
		}
	}
	return false
}

func CleanOrphanedResources(w http.ResponseWriter, r *http.Request) {
	ctx := context.Background()
	computeService, err := compute.NewService(ctx)
	if err != nil {
		log.Fatalf("Error creating compute service: %v", err)
	}

	project := os.Getenv("GCP_DEV_PROJECT")
	if project == "" {
		log.Fatalf("GCP_DEV_PROJECT is not set")
	}

	firewallsList, err := computeService.Firewalls.List(project).Context(ctx).Do()
	if err != nil {
		log.Fatalf("Error listing firewalls: %v", err)
	}

	firewallByName := make(map[string]*compute.Firewall)
	for _, fw := range firewallsList.Items {
		firewallByName[fw.Name] = fw
	}

	// Get aggregated target pools
	targetPoolAggregatedList, err := computeService.TargetPools.AggregatedList(project).Context(ctx).Do()
	if err != nil {
		log.Fatalf("Error getting target pool aggregated list: %v", err)
	}

	for _, targetPoolList := range targetPoolAggregatedList.Items {
		for _, targetPoolItem := range targetPoolList.TargetPools {
			// Reset toDelete per target pool
			toDelete := false

			region := strings.Split(targetPoolItem.Region, "/")[8]

			// If pool has instances, check their existence. If instance missing -> mark toDelete.
			if len(targetPoolItem.Instances) > 0 {
				instanceName := strings.Split(targetPoolItem.Instances[0], "/")[10]
				zone := strings.Split(targetPoolItem.Instances[0], "/")[8]
				_, err := computeService.Instances.Get(project, zone, instanceName).Context(ctx).Do()
				if err != nil {
					// If instance not found (404), mark target pool for deletion.
					if strings.Contains(err.Error(), "404") {
						toDelete = true
					} else {
						// For other errors, log and continue to next item
						log.Printf("Error checking instance %s in zone %s: %v", instanceName, zone, err)
						continue
					}
				}
			}

			// If pool is empty or flagged for deletion, find related resources to remove
			if len(targetPoolItem.Instances) == 0 || toDelete {
				// Collect firewall rules that are candidate to be deleted for this target pool.
				// We look for firewalls that either contain the pool name or match our name patterns.
				candidates := []string{}
				for name, fw := range firewallByName {
					// Candidate if name contains targetPool name OR matches patterns
					if strings.Contains(name, targetPoolItem.Name) || matchesFirewallPattern(name) {
						if len(fw.TargetTags) == 0 {
							continue
						}
						// If any tag contains the pool name, mark candidate
						foundTag := false
						for _, tag := range fw.TargetTags {
							if strings.Contains(tag, targetPoolItem.Name) || matchesFirewallPattern(tag) {
								foundTag = true
								break
							}
						}
						if foundTag {
							candidates = append(candidates, name)
						}
					}
				}

				// For each candidate firewall rule verify it's not used by any instance
				for _, fwName := range candidates {
					fw := firewallByName[fwName]
					used := false
					// For each target tag, check if any instance uses it in the project
					for _, tag := range fw.TargetTags {
						// list instances filtered by tag
						// Here we use Instances.AggregatedList and filter manually.
						instancesAgg, err := computeService.Instances.AggregatedList(project).Filter(fmt.Sprintf("tags.items=%s", tag)).Context(ctx).Do()
						if err != nil {
							log.Printf("Error listing instances for tag %s: %v", tag, err)
							used = true
							break
						}
						for _, il := range instancesAgg.Items {
							if len(il.Instances) > 0 {
								used = true
								break
							}
						}
						if used {
							break
						}
					}

					if used {
						log.Printf("Skipping firewall %s: target tags are in use", fwName)
						continue
					}

					// Safe to delete firewall rule
					_, err := computeService.Firewalls.Delete(project, fwName).Context(ctx).Do()
					if err != nil {
						log.Printf("Failed to delete firewall %s: %v", fwName, err)
					} else {
						log.Printf("Firewall %s deleted", fwName)
					}
				}

				// Delete forwarding rule (if exists)
				respFWRule, err := computeService.ForwardingRules.Delete(project, region, targetPoolItem.Name).Context(ctx).Do()
				if err != nil {
					log.Printf("Can't delete Forwarding rule %s in %s: %v", targetPoolItem.Name, region, err)
				} else {
					log.Printf("forwarding-rule deleted: %s status %s", targetPoolItem.Name, respFWRule.Status)
				}

				// Delete address (if exists)
				respAdd, err := computeService.Addresses.Delete(project, region, targetPoolItem.Name).Context(ctx).Do()
				if err != nil {
					log.Printf("Can't delete Address %s in %s: %v", targetPoolItem.Name, region, err)
				} else {
					log.Printf("address deleted: %s status %s", targetPoolItem.Name, respAdd.Status)
				}

				// Delete target pool
				respTP, err := computeService.TargetPools.Delete(project, region, targetPoolItem.Name).Context(ctx).Do()
				if err != nil {
					log.Printf("Can't delete target pool %s in %s: %v", targetPoolItem.Name, region, err)
				} else {
					log.Printf("Target-pool deleted: %s status %s", targetPoolItem.Name, respTP.Status)
				}
			}
		}
	}
}

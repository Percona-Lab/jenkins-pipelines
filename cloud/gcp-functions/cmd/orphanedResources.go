package orphanedResources

import (
	"context"
	"log"
	"net/http"
	"os"
	"strings"

	"google.golang.org/api/compute/v1"
)

func matchesFirewallPattern(name string) bool {
	if strings.HasPrefix(name, "gke-jen") || strings.HasPrefix(name, "k8s-") {
		return true
	}
	return false
}

func tagUsed(usedTags map[string]struct{}, tags []string) bool {
	for _, t := range tags {
		if _, ok := usedTags[t]; ok {
			return true
		}
	}
	return false
}

// CleanOrphanedResources deletes orphan TargetPools (+ related ForwardingRules/Addresses/firewalls)
// and also deletes dangling firewall rules whose target tags are unused.
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

	// 1) Fetch firewalls (index by name)
	fwList, err := computeService.Firewalls.List(project).Context(ctx).Do()
	if err != nil {
		log.Fatalf("Error listing firewalls: %v", err)
	}
	firewallByName := make(map[string]*compute.Firewall, len(fwList.Items))
	for _, fw := range fwList.Items {
		firewallByName[fw.Name] = fw
	}

	// 2) Build a set of all in-use tags from all instances
	usedTags := make(map[string]struct{}, 1024)
	instAgg, err := computeService.Instances.AggregatedList(project).Context(ctx).Do()
	if err != nil {
		log.Printf("Error aggregating instances (building usedTags): %v", err)
	}
	if instAgg != nil {
		for _, il := range instAgg.Items {
			for _, ins := range il.Instances {
				for _, t := range ins.Tags.Items {
					usedTags[t] = struct{}{}
				}
			}
		}
	}
	log.Printf("Collected %d used tags", len(usedTags))

	// 3) Fetch aggregated TargetPools
	tpAgg, err := computeService.TargetPools.AggregatedList(project).Context(ctx).Do()
	if err != nil {
		log.Fatalf("Error getting target pool aggregated list: %v", err)
	}

	deletedAny := false
	deletedFW := make(map[string]struct{}) // remember deleted firewall names to avoid double-delete later

	// 4) Delete orphan TargetPools and their related resources and related firewalls if tags unused
	for _, tpList := range tpAgg.Items {
		for _, tp := range tpList.TargetPools {
			region := strings.Split(tp.Region, "/")[8]

			toDelete := false
			// orphan if no instances OR the first instance ref returns 404
			if len(tp.Instances) == 0 {
				toDelete = true
			} else {
				instanceName := strings.Split(tp.Instances[0], "/")[10]
				zone := strings.Split(tp.Instances[0], "/")[8]

				_, gerr := computeService.Instances.Get(project, zone, instanceName).Context(ctx).Do()
				if gerr != nil && strings.Contains(gerr.Error(), "404") {
					toDelete = true
				}
				if gerr != nil && !strings.Contains(gerr.Error(), "404") {
					log.Printf("Error checking instance %s in %s: %v", instanceName, zone, gerr)
					continue
				}
			}

			if !toDelete {
				continue
			}

			// Build candidate firewalls to delete that are "related" to this TP
			var candidates []string
			for name, fw := range firewallByName {
				if fw == nil {
					continue
				}
				if strings.Contains(name, tp.Name) || matchesFirewallPattern(name) {
					if len(fw.TargetTags) == 0 {
						candidates = append(candidates, name)
						continue
					}
					// only consider if FW tags "look related" to this TP
					related := false
					for _, tag := range fw.TargetTags {
						if strings.Contains(tag, tp.Name) || matchesFirewallPattern(tag) {
							related = true
							break
						}
					}
					if related {
						candidates = append(candidates, name)
					}
				}
			}

			// Delete candidate firewalls when tags are NOT used
			for _, fname := range candidates {
				fw := firewallByName[fname]
				if fw == nil {
					continue
				}
				if len(fw.TargetTags) == 0 || !tagUsed(usedTags, fw.TargetTags) {
					if _, derr := computeService.Firewalls.Delete(project, fname).Context(ctx).Do(); derr != nil {
						log.Printf("Failed to delete firewall %s: %v", fname, derr)
					} else {
						deletedAny = true
						deletedFW[fname] = struct{}{}
						firewallByName[fname] = nil
					}
				} else {
					log.Printf("Skipping firewall %s: target tags are still in use\n", fname)
				}
			}

			// Delete ForwardingRule
			if _, err := computeService.ForwardingRules.Delete(project, region, tp.Name).Context(ctx).Do(); err != nil {
				log.Printf("Can't delete Forwarding rule %s in %s: %v", tp.Name, region, err)
			} else {
				log.Printf("Forwarding-rule deleted: %s\n", tp.Name)
				deletedAny = true
			}

			// Delete Address
			if _, err := computeService.Addresses.Delete(project, region, tp.Name).Context(ctx).Do(); err != nil {
				log.Printf("Can't delete Address %s in %s: %v", tp.Name, region, err)
			} else {
				log.Printf("Address deleted: %s\n", tp.Name)
				deletedAny = true
			}

			// Delete the TargetPool
			if _, err := computeService.TargetPools.Delete(project, region, tp.Name).Context(ctx).Do(); err != nil {
				log.Printf("Can't delete target pool %s in %s: %v", tp.Name, region, err)
			} else {
				log.Printf("Target-pool deleted: %s\n", tp.Name)
				deletedAny = true
			}
		}
	}

	// 5) Delete any remaining dangling firewalls
	//    that match our patterns and have unused tags. This catches cases with no orphan TP trigger.
	for name, fw := range firewallByName {
		if fw == nil {
			continue
		}
		if !matchesFirewallPattern(name) {
			continue
		}
		// no tags -> dangling
		if len(fw.TargetTags) == 0 || !tagUsed(usedTags, fw.TargetTags) {
			if _, derr := computeService.Firewalls.Delete(project, name).Context(ctx).Do(); derr != nil {
				log.Printf("Failed to delete dangling firewall %s: %v", name, derr)
			} else {
				log.Printf("Dangling firewall %s deleted\n", name)
				deletedAny = true
				firewallByName[name] = nil
			}
		}
	}

	if !deletedAny {
		log.Println("No orphaned resources found or deleted")
	}

	w.WriteHeader(http.StatusOK)
}

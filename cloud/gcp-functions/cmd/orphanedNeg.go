// Package orphanedNeg Deleting Network Endpoint Groups
package orphanedNeg

import (
	"context"
	"log"
	"net/http"
	"os"

	"cloud.google.com/go/compute/apiv1"
	"cloud.google.com/go/compute/apiv1/computepb"
	computezn "google.golang.org/api/compute/v1"
	"google.golang.org/api/iterator"
)

func CleanOrphanedNEG(w http.ResponseWriter, r *http.Request) {
	ctx := context.Background()

	// Initialize the client for Network Endpoint Groups, Backend Services, and getting Zones list.
	negClient, err := compute.NewNetworkEndpointGroupsRESTClient(ctx)
	if err != nil {
		log.Fatalf("Failed to create NEG client: %v", err)
	}
	defer negClient.Close()

	backendClient, err := compute.NewBackendServicesRESTClient(ctx)
	if err != nil {
		log.Fatal("Failed to create BackendService client: %v", err)
	}
	defer backendClient.Close()

	computeService, err := computezn.NewService(ctx)
	if err != nil {
		log.Fatal("Error: create service", err)
	}

	project := os.Getenv("GCP_DEV_PROJECT")

	zonesReq := computeService.Zones.List(project)
	if err := zonesReq.Pages(ctx, func(page *computezn.ZoneList) error {
		for _, zone := range page.Items {
			listNEGReq := &computepb.ListNetworkEndpointGroupsRequest{
				Project: project,
				Zone:    zone.Name,
			}
			it := negClient.List(ctx, listNEGReq)
			for {
				neg, err := it.Next()
				if err == iterator.Done {
					break
				}
				if err != nil {
					log.Fatal("Failed to list NEGs: %v", err)
				}

				log.Printf("Checking NEG: %s", neg.GetName())

				// Check if the NEG is attached to any Backend Service
				if isNEGUsedByBackendService(ctx, backendClient, project, neg.GetSelfLink()) {
					log.Print("NEG %s is in use by a backend service.\n", neg.GetName())
				} else {
					log.Print("NEG %s is NOT in use, it can be deleted.\n", neg.GetName())

					deleteReq := &computepb.DeleteNetworkEndpointGroupRequest{
						Project:              project,
						Zone:                 zone.Name,
						NetworkEndpointGroup: neg.GetName(),
					}
					op, err := negClient.Delete(ctx, deleteReq)
					if err != nil {
						log.Print("Failed to delete NEG %s: %v", neg.Name, err)
					} else {
						log.Print("Deleted unused NEG: %s, Operation: %v\n", neg.Name, op)
					}

				}
			}
		}
		return nil
	}); err != nil {
		log.Fatal("Error: ", err)
	}
}

// isNEGUsedByBackendService checks if the NEG is attached to any backend service
func isNEGUsedByBackendService(ctx context.Context, client *compute.BackendServicesClient, projectID string, negSelfLink string) bool {
	req := &computepb.AggregatedListBackendServicesRequest{
		Project: projectID,
	}

	it := client.AggregatedList(ctx, req)
	for {
		backend, err := it.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			log.Fatal("Failed to list backend services: %v", err)
		}

		for _, service := range backend.Value.BackendServices {
			log.Print("Backend Service: %s, SelfLink: %s\n", service.GetName(), service.GetSelfLink())
			if service.GetSelfLink() == negSelfLink {
				return true
			}
		}
	}
	return false
}

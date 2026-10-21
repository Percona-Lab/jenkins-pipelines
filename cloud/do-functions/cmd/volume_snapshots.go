package main

import (
	"context"
	"encoding/json"
	"io"
	"net/url"
	"time"
)

type snapshot struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Tags         []string `json:"tags"`
	ResourceID   string   `json:"resource_id"`
	ResourceType string   `json:"resource_type"`
	CreatedAt    string   `json:"created_at"`
}

func cleanVolumeSnapshots(ctx context.Context, client *apiClient, snapshots []snapshot, volumes []volume, now time.Time, dryRun bool, cleanupErrors *[]string) ResourceSummary {
	volumeIDs := make(map[string]struct{}, len(volumes))
	for _, volume := range volumes {
		volumeIDs[volume.ID] = struct{}{}
	}

	summary := ResourceSummary{Scanned: len(snapshots)}
	for _, snapshot := range snapshots {
		_, volumeExists := volumeIDs[snapshot.ResourceID]
		if hasTag(snapshot.Tags, skipDeletionTag) || volumeExists || !olderThan(snapshot.CreatedAt, now, orphanGracePeriod) {
			summary.Skipped = append(summary.Skipped, snapshot.Name)
			continue
		}

		summary.Candidates = append(summary.Candidates, snapshot.Name)
		if deleteCandidate(ctx, "volume snapshot", snapshot.Name, "source volume no longer exists", client.deleteSnapshot, snapshot.ID, dryRun, cleanupErrors) {
			summary.Deleted = append(summary.Deleted, snapshot.Name)
		}
	}
	return summary
}

func (client *apiClient) listVolumeSnapshots(ctx context.Context) ([]snapshot, error) {
	var resources []snapshot
	err := client.listPages(ctx, "/v2/snapshots?resource_type=volume&per_page=200&page=1", func(body io.Reader) (string, error) {
		var page struct {
			Snapshots []snapshot `json:"snapshots"`
			Links     pageLinks  `json:"links"`
		}
		if err := json.NewDecoder(body).Decode(&page); err != nil {
			return "", err
		}
		resources = append(resources, page.Snapshots...)
		return page.Links.Pages.Next, nil
	})
	return resources, err
}

func (client *apiClient) deleteSnapshot(ctx context.Context, id string) error {
	return client.delete(ctx, "/v2/snapshots/"+url.PathEscape(id))
}

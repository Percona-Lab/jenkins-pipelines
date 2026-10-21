package main

import (
	"context"
	"encoding/json"
	"io"
	"net/url"
	"time"
)

type volume struct {
	ID         string   `json:"id"`
	Name       string   `json:"name"`
	Tags       []string `json:"tags"`
	DropletIDs []int    `json:"droplet_ids"`
	CreatedAt  string   `json:"created_at"`
}

func cleanVolumes(ctx context.Context, client *apiClient, volumes []volume, now time.Time, dryRun bool, cleanupErrors *[]string) ResourceSummary {
	summary := ResourceSummary{Scanned: len(volumes)}
	for _, volume := range volumes {
		if hasTag(volume.Tags, skipDeletionTag) || len(volume.DropletIDs) > 0 || !olderThan(volume.CreatedAt, now, orphanGracePeriod) {
			summary.Skipped = append(summary.Skipped, volume.Name)
			continue
		}

		summary.Candidates = append(summary.Candidates, volume.Name)
		if deleteCandidate(ctx, "volume", volume.Name, "not attached to a Droplet", client.deleteVolume, volume.ID, dryRun, cleanupErrors) {
			summary.Deleted = append(summary.Deleted, volume.Name)
		}
	}
	return summary
}

func (client *apiClient) listVolumes(ctx context.Context) ([]volume, error) {
	var resources []volume
	err := client.listPages(ctx, "/v2/volumes?per_page=200&page=1", func(body io.Reader) (string, error) {
		var page struct {
			Volumes []volume  `json:"volumes"`
			Links   pageLinks `json:"links"`
		}
		if err := json.NewDecoder(body).Decode(&page); err != nil {
			return "", err
		}
		resources = append(resources, page.Volumes...)
		return page.Links.Pages.Next, nil
	})
	return resources, err
}

func (client *apiClient) deleteVolume(ctx context.Context, id string) error {
	return client.delete(ctx, "/v2/volumes/"+url.PathEscape(id))
}

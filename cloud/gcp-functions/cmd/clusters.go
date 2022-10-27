package clusters

import (
        "log"
        "net/http"
        "context"
        "os"
        "time"
        "strconv"
        "math"

        "google.golang.org/api/compute/v1"
        "google.golang.org/api/container/v1"
)

func CleanClusters(w http.ResponseWriter, r *http.Request) {
    ctx := context.Background()
    computeService, err := compute.NewService(ctx)
    if err != nil {
        log.Fatal("Error: create service", err)
    }
    containerService, errContainer := container.NewService(ctx)
    if errContainer != nil {
        log.Fatal("Error: create containerService", errContainer)
    }

    project := os.Getenv("GCP_DEV_PROJECT")
    zones := computeService.Zones.List(project)
    currentTime := time.Now()

    if err := zones.Pages(ctx, func(page *compute.ZoneList) error {
        for _, zone := range page.Items {
            clusters, err := containerService.Projects.Zones.Clusters.List(project, zone.Name).Do()
            if err != nil {
                log.Printf("Getting clusters in zone %s failed with error: %v", zone.Name, err)
                continue
            }
            for _, cluster := range clusters.Clusters {
                creationTime, err := time.Parse(time.RFC3339, cluster.CreateTime)
                if err != nil {
                    log.Printf("Error getting cluster creation time : %v", err)
                    continue
                }

                deleteClusterAfterHours, ok := cluster.ResourceLabels["delete-cluster-after-hours"]
                if !ok {
                    continue
                }

                deleteClusterAfterHoursInt, err := strconv.ParseInt(deleteClusterAfterHours, 10, 64)
                if err != nil {
                    log.Printf("Parse label value: %v", err)
                    continue
                }

                if int64(math.Round(currentTime.Sub(creationTime).Hours())) > deleteClusterAfterHoursInt {
                    resp, err := containerService.Projects.Zones.Clusters.Delete(project, zone.Name, cluster.Name).Context(ctx).Do()
                    if err != nil {
                        log.Printf("Cluster deletion error: %v", err)
                        continue
                    }

                    log.Printf("Cluster: %s in zone %s was deleted with status %s\n", cluster.Name, zone.Name, resp.Status)
                }
            }
        }
        return nil
    }); err != nil {
        log.Fatal("Error: ", err)
    }
}

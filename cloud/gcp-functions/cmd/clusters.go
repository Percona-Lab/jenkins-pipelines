package clusters

import (
        "fmt"
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
    fmt.Fprint(w, "Hello, World test!")
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
    zonesReq := computeService.Zones.List(project)
    currentTime := time.Now()

    if err := zonesReq.Pages(ctx, func(page *compute.ZoneList) error {
        for _, zone := range page.Items {
            clustersReq, err := containerService.Projects.Zones.Clusters.List(project, zone.Name).Do()
            if err != nil {
                log.Fatal(err)
            }
            for _, cluster := range clustersReq.Clusters {
                creationTime, err := time.Parse(time.RFC3339, cluster.CreateTime)
                if err != nil {
                    log.Fatal(err)
                }

                deleteClusterAfterHours := cluster.ResourceLabels["delete-cluster-after-hours"]

                if len(deleteClusterAfterHours) != 0 {
                    if deleteClusterAfterHours, err := strconv.ParseInt(deleteClusterAfterHours, 10, 64); err == nil {
                        if int64(math.Round(currentTime.Sub(creationTime).Hours())) > deleteClusterAfterHours {
                            resp, err := containerService.Projects.Zones.Clusters.Delete(project, zone.Name, cluster.Name).Context(ctx).Do()
                            if err != nil {
                                return fmt.Errorf("delete cluster: %v", err)
                            }
                            fmt.Printf("%s\n", resp.Status)
                            fmt.Printf("cluster: %s in zone %s was deleted\n", cluster.Name, zone.Name)
                        }
                    }
                }

            }
        }
        return nil
    }); err != nil {
        log.Fatal("Error: ", err)
    }
}

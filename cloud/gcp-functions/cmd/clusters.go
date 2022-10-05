package clusters

import (
        "fmt"
        "log"
        "net/http"
        "context"
        "os"
        "time"
        "strconv"

        "google.golang.org/api/container/v1"
        "google.golang.org/api/compute/v1"
)

func CleanClusters(http.ResponseWriter, *http.Request) {
    ctx := context.Background()
    containerService, errContainer := container.NewService(ctx)
    if errContainer != nil {
        log.Fatal("Error: create containerService", errContainer)
    }

    computeService, errCompute := compute.NewService(ctx)
    if errCompute != nil {
        log.Fatal("Error: create computeService", errCompute)
    }
    project := os.Getenv("GCP_DEV_PROJECT")
    zonesReq := computeService.Zones.List(project)
    currentTime := time.Now().Unix()
    if err := zonesReq.Pages(ctx, func(page *compute.ZoneList) error {
        for _, zone := range page.Items {
            clustersReq, err := containerService.Projects.Zones.Clusters.List(project, zone.Name).Context(ctx).Do()
            if err != nil {
                log.Fatal(err)
            }
            for _, cluster := range clustersReq.Clusters {
                creationTime := cluster.ResourceLabels["creation-time"]
                log.Println("This is stderr: %s", creationTime )
                log.Println("This is stderr: %s", cluster.Name )
                if len(creationTime) != 0 {
                    if creationTime, err := strconv.ParseInt(creationTime, 10, 64); err == nil {
                        if (currentTime - creationTime) > 1 {
                            log.Println("Try to delete")
                            resp, err := containerService.Projects.Zones.Clusters.Delete(project, zone.Name, cluster.Name).Context(ctx).Do()
                            if err != nil {
                                log.Println("delete cluster: %v", err)
                                return fmt.Errorf("delete cluster: %v", err)
                            }
                            fmt.Printf("%s\n", resp)
                            fmt.Printf("cluster: %s in zone %s was deleted\n", cluster.Name, zone.Name)
                        }

                    }
                }
            }
            return nil
        }
        return nil
    }); err != nil {
        log.Fatal("Error: ", err)
    }
}

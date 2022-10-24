package clusters

import (
        "fmt"
        "log"
        "net/http"
        "context"
        "os"
        "time"
        "strconv"

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
        fmt.Fprintln(w, "Error: create containerService", errContainer)
    }

    project := os.Getenv("GCP_DEV_PROJECT")
    zonesReq := computeService.Zones.List(project)
    currentTime := time.Now().Unix()
    fmt.Fprintln(w, "Сurrent time %s", currentTime)

    if err := zonesReq.Pages(ctx, func(page *compute.ZoneList) error {
        for _, zone := range page.Items {
            clustersReq, err := containerService.Projects.Zones.Clusters.List(project, zone.Name).Do()
            if err != nil {
                log.Fatal(err)
            }
            fmt.Fprintln(w, "clusters: %v" ,clustersReq )
            for _, cluster := range clustersReq.Clusters {
                fmt.Fprintln(w, "Iterate clusters %s", cluster.Name)
                creationTime := cluster.ResourceLabels["creation-time"]

                if len(creationTime) != 0 {
                    if creationTime, err := strconv.ParseInt(creationTime, 10, 64); err == nil {
                        fmt.Fprintln(w, "Creation time %s", creationTime)
                        if (currentTime - creationTime)/3600 > 6 {
                            fmt.Fprint(w, "Try to delete")
                            resp, err := containerService.Projects.Zones.Clusters.Delete(project, zone.Name, cluster.Name).Context(ctx).Do()
                            if err != nil {
                                fmt.Fprint(w, "%s\n", err)
                                return fmt.Errorf("delete cluster: %v", err)
                            }

                            fmt.Fprint(w, "%s\n", resp)
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

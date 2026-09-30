package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"

	"github.com/dattapool/mvp/internal/capture"
	"github.com/dattapool/mvp/internal/nostr"
	"github.com/dattapool/mvp/pkg/protocol"
)

func main() {
	port := flag.Int("port", 8080, "HTTP server port")
	storeDir := flag.String("store_dir", "./session_data/nostr_store", "Nostr event store directory")
	flag.Parse()

	_ = os.MkdirAll(*storeDir, 0755)

	http.HandleFunc("/freshness", func(w http.ResponseWriter, r *http.Request) {
		freshness := capture.QueryBitcoinFreshness()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(freshness)
	})

	http.HandleFunc("/nostr/event", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "Failed to read body", http.StatusBadRequest)
			return
		}

		var evt nostr.Event
		if err := json.Unmarshal(body, &evt); err != nil {
			http.Error(w, fmt.Sprintf("Invalid Nostr event JSON: %v", err), http.StatusBadRequest)
			return
		}

		if !evt.Verify() {
			http.Error(w, "Nostr event signature verification failed", http.StatusUnprocessableEntity)
			return
		}

		publisher := nostr.NewMockWitnessPublisher(*storeDir)
		res, err := publisher.PublishSessionStart(r.Context(), &evt)
		if err != nil {
			http.Error(w, fmt.Sprintf("Publish failed: %v", err), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(res)
		log.Printf("Witness event %s published successfully from %s", evt.ID, r.RemoteAddr)
	})

	http.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		sessionID := r.URL.Query().Get("session_id")
		if sessionID == "" {
			sessionID = "android_session"
		}
		destDir := filepath.Join("./session_data", fmt.Sprintf("upload-%s", sessionID))
		_ = os.MkdirAll(destDir, 0755)

		file, header, err := r.FormFile("bundle")
		if err != nil {
			http.Error(w, "Missing bundle form file", http.StatusBadRequest)
			return
		}
		defer file.Close()

		destFile := filepath.Join(destDir, header.Filename)
		out, err := os.Create(destFile)
		if err != nil {
			http.Error(w, "Failed to save file", http.StatusInternalServerError)
			return
		}
		defer out.Close()
		_, _ = io.Copy(out, file)

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"success": true,
			"saved":   destFile,
		})
		log.Printf("Received upload for session %s: %s", sessionID, destFile)
	})

	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"status":   "ok",
			"protocol": protocol.ProtocolName,
			"version":  protocol.SchemaVersion,
		})
	})

	addr := fmt.Sprintf("0.0.0.0:%d", *port)
	log.Printf("DattaPool Dev Freshness & Witness Service running on %s", addr)
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}

package main

import (
	"os"
	"path/filepath"
	"testing"

	"launcher/pkg/launch"
)

func TestExtractPublishedAssets(t *testing.T) {
	destination := os.Getenv("LAUNCHER_EXTRACT_TEST_DIR")
	if destination == "" {
		t.Skip("release assets supplied by Windows packaging CI")
	}
	if _, err := os.Stat(destination); !os.IsNotExist(err) {
		t.Fatal("asset extraction requires a new destination")
	}
	if err := launch.ExtractAssets(embeddedBin, destination); err != nil {
		t.Fatal(err)
	}
	for _, relative := range []string{"bin/cloudflared.exe", "bin/cloudflared-notices/source-manifest.json", "bin/qqmusic/python/python.exe"} {
		if _, err := os.Stat(filepath.Join(destination, filepath.FromSlash(relative))); err != nil {
			t.Fatal(err)
		}
	}
}

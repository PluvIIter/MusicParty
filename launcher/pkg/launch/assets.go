package launch

import (
	"bytes"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

func ExtractAssets(source fs.FS, destination string) error {
	if err := fs.WalkDir(source, "bin", func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if path == "bin/qqmusic" {
			return fs.SkipDir
		}
		target := filepath.Join(destination, filepath.FromSlash(path))
		if entry.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		data, err := fs.ReadFile(source, path)
		if err != nil {
			return err
		}
		if existing, err := os.ReadFile(target); err == nil && bytes.Equal(existing, data) {
			return nil
		}
		file, err := os.CreateTemp(filepath.Dir(target), ".asset-*")
		if err != nil {
			return err
		}
		temporary := file.Name()
		defer func() {
			if filepath.Dir(temporary) == filepath.Dir(target) && strings.HasPrefix(filepath.Base(temporary), ".asset-") {
				os.Remove(temporary)
			}
		}()
		if err = file.Chmod(0755); err == nil {
			_, err = file.Write(data)
		}
		if err == nil {
			err = file.Sync()
		}
		closeErr := file.Close()
		if err != nil {
			return err
		}
		if closeErr != nil {
			return closeErr
		}
		return os.Rename(temporary, target)
	}); err != nil {
		return err
	}
	if _, err := fs.Stat(source, "bin/qqmusic"); os.IsNotExist(err) {
		return nil
	} else if err != nil {
		return err
	}
	bin, err := filepath.Abs(filepath.Join(destination, "bin"))
	if err != nil {
		return err
	}
	stage, err := os.MkdirTemp(bin, ".qqmusic-stage-")
	if err != nil {
		return err
	}
	defer removeOwnedTree(bin, stage, ".qqmusic-stage-")
	if err := fs.WalkDir(source, "bin/qqmusic", func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		relative := strings.TrimPrefix(path, "bin/qqmusic")
		target := filepath.Join(stage, filepath.FromSlash(strings.TrimPrefix(relative, "/")))
		if entry.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		data, err := fs.ReadFile(source, path)
		if err != nil {
			return err
		}
		return os.WriteFile(target, data, 0755)
	}); err != nil {
		return err
	}
	target := filepath.Join(bin, "qqmusic")
	backup, err := os.MkdirTemp(bin, ".qqmusic-backup-")
	if err != nil {
		return err
	}
	if filepath.Dir(backup) != bin || !strings.HasPrefix(filepath.Base(backup), ".qqmusic-backup-") {
		return fmt.Errorf("invalid asset backup path")
	}
	if err := os.Remove(backup); err != nil {
		return err
	}
	hadPrevious := false
	if _, err := os.Lstat(target); err == nil {
		if filepath.Dir(target) != bin || filepath.Base(target) != "qqmusic" {
			return fmt.Errorf("invalid QQ asset path")
		}
		if err := os.Rename(target, backup); err != nil {
			return fmt.Errorf("QQ runtime is in use; close other launchers before updating")
		}
		hadPrevious = true
	} else if !os.IsNotExist(err) {
		return err
	}
	if err := os.Rename(stage, target); err != nil {
		if hadPrevious {
			os.Rename(backup, target)
		}
		return err
	}
	if hadPrevious {
		return removeOwnedTree(bin, backup, ".qqmusic-backup-")
	}
	return nil
}

func removeOwnedTree(parent, path, prefix string) error {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	if filepath.Dir(absolute) != parent || !strings.HasPrefix(filepath.Base(absolute), prefix) {
		return fmt.Errorf("refusing to remove unrelated asset path")
	}
	return os.RemoveAll(absolute)
}

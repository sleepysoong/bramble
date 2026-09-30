//go:build unix

package relay

import (
	"errors"
	"os"
	"syscall"
)

func validateDirectoryOwner(info os.FileInfo) error {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || uint32(os.Geteuid()) != stat.Uid {
		return errors.New("upload directory must belong to the current user")
	}
	return nil
}

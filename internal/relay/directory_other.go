//go:build !unix

package relay

import "os"

func validateDirectoryOwner(os.FileInfo) error { return nil }

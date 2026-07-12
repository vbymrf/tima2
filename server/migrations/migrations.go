// Package migrations — встроенные SQL-миграции (применяются `tima migrate` по порядку имён).
package migrations

import "embed"

//go:embed *.sql
var FS embed.FS

#!/usr/bin/env sh
# Генерация Go-классов конверта из schema/proto (запускать из server/).
# Требует: buf и protoc-gen-go в PATH (go install github.com/bufbuild/buf/cmd/buf@latest
#          и google.golang.org/protobuf/cmd/protoc-gen-go@latest).
set -e
cd "$(dirname "$0")/.."
buf generate ../schema/proto --template buf.gen.yaml
echo "OK: internal/proto обновлён из ../schema/proto"

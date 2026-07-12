# Stub-анклав escrow (escrow-legal-access.md §7): отдельный контейнер,
# состояние (ключ, аудит) — только на его volume; tima ходит лишь в /v1/pubkey.
# Сборка из корня server/:  docker build -f deploy/escrow-stub.Dockerfile -t tima-escrow-stub .
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd/escrow-stub ./cmd/escrow-stub
COPY internal/escrow ./internal/escrow
RUN CGO_ENABLED=0 go build -o /escrow-stub ./cmd/escrow-stub

FROM alpine:3.20
COPY --from=build /escrow-stub /usr/local/bin/escrow-stub
ENV ESCROW_STATE_DIR=/data ESCROW_LISTEN=:8090
VOLUME /data
EXPOSE 8090
ENTRYPOINT ["escrow-stub"]

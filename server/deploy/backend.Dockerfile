# Бэкенд TIMA (модульный монолит: serve | worker | migrate) — один статический бинарник.
# Сборка из каталога server/:  docker build -f deploy/backend.Dockerfile -t tima-backend .
# Миграции вшиты в бинарник (migrations.FS, //go:embed) — отдельный volume не нужен.
FROM golang:1.26-alpine AS build
WORKDIR /src
# Кэш зависимостей отдельным слоем: пока go.mod/go.sum не менялись — не перекачиваем
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# CGO не нужен (pgx, redis, minio, websocket — чистый Go): статический бинарник под scratch/alpine
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /tima ./cmd/tima

FROM alpine:3.20
# wget для healthcheck (compose), ca-certificates для исходящего TLS (SMS/push-провайдеры)
RUN apk add --no-cache ca-certificates wget && adduser -D -u 10001 tima
COPY --from=build /tima /usr/local/bin/tima
USER tima
EXPOSE 8080
ENTRYPOINT ["tima"]
CMD ["serve"]

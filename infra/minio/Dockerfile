FROM golang:1.24.13-alpine AS build
ENV CGO_ENABLED=0
RUN go install github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z

FROM alpine:3.23.3
RUN mkdir /data && chown 10001:10001 /data
COPY --from=build /go/bin/minio /usr/local/bin/minio
USER 10001:10001
ENTRYPOINT ["minio"]

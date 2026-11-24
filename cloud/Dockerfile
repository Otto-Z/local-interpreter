# Multi-stage Dockerfile for Google Cloud Run deployment.
# Build from repository root:
#   docker build -f cloud/Dockerfile -t local-interpreter-cloud .

FROM rust:1.83-slim-bookworm AS builder
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config ca-certificates && rm -rf /var/lib/apt/lists/*
COPY Cargo.toml ./Cargo.toml
COPY core ./core
COPY cloud ./cloud
RUN cargo build --release -p local-interpreter-cloud

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /src/target/release/local-interpreter-cloud /usr/local/bin/local-interpreter-cloud
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/local-interpreter-cloud"]

# syntax=docker/dockerfile:1
FROM ghcr.io/astral-sh/uv:0.13.1@sha256:4a1f3666816dafadd20ceb71eac9a7d56d7ef30279f7d64048519ab901ada336 AS uv
FROM python:3.13.16-slim-bookworm@sha256:73aa5faee130c60da030e105ec971ccf57473fbd22ce73bd6fc9ba99a40b838d AS build
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
COPY simulator/pyproject.toml simulator/uv.lock ./
ENV UV_PYTHON_DOWNLOADS=never UV_LINK_MODE=copy
RUN uv sync --locked --no-dev --no-install-project --python /usr/local/bin/python

FROM python:3.13.16-slim-bookworm@sha256:73aa5faee130c60da030e105ec971ccf57473fbd22ce73bd6fc9ba99a40b838d AS runtime
RUN groupadd --gid 10001 vortex && useradd --uid 10001 --gid vortex --no-create-home vortex \
    && mkdir -p /var/lib/vortex/journal && chown -R 10001:10001 /var/lib/vortex
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY simulator/src /app/src
COPY docker/simulator/healthcheck.py /app/healthcheck.py
ENV PATH="/app/.venv/bin:$PATH" PYTHONPATH=/app/src PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 VORTEX_COMMAND_JOURNAL=/var/lib/vortex/journal/commands.jsonl
USER 10001:10001
HEALTHCHECK --interval=15s --timeout=8s --start-period=30s --retries=8 \
  CMD ["python", "/app/healthcheck.py"]
ENTRYPOINT ["python", "-m", "vortex_simulator.main"]


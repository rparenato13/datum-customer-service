FROM python:3.13.18-slim-bookworm
WORKDIR /workspace
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY infra/rabbitmq/model.py infra/rabbitmq/bootstrap.py ./infra/rabbitmq/
COPY contracts/events/topology.v1.json ./contracts/events/topology.v1.json
# Job sem Docker socket/rede; biblioteca padrao, sem instalacao via pip.
ENTRYPOINT ["python", "/workspace/infra/rabbitmq/bootstrap.py"]

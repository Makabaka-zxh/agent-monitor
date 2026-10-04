FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt requirements-tested.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY agent_monitor ./agent_monitor
COPY web ./web
RUN useradd --create-home --uid 10001 monitor && mkdir -p /data && chown monitor:monitor /data
USER monitor
EXPOSE 8766
ENTRYPOINT ["python", "-m", "agent_monitor", "--host", "0.0.0.0", "--port", "8766", "--no-local", "--state-dir", "/data"]

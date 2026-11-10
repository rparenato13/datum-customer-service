FROM python:3.13.18-slim-bookworm AS distribution
COPY performance/jmeter/install.py /install.py
RUN python /install.py

FROM eclipse-temurin:21-jre-jammy
COPY --from=distribution /opt/apache-jmeter-5.6.3 /opt/jmeter
ENV PATH="/opt/jmeter/bin:${PATH}" HEAP="-Xms128m -Xmx256m" JVM_ARGS="-XX:ActiveProcessorCount=1 -Duser.home=/tmp"
USER 10001:10001
WORKDIR /tmp
ENTRYPOINT ["jmeter"]
# Construir/iniciar a imagem sem plano nao executa carga.
CMD ["--version"]

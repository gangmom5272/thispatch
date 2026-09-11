# Jenkins + docker CLI
#
# 공식 jenkins/jenkins 이미지에는 docker 명령이 없다. 소켓만 마운트해도
# 컨테이너 안에 쓸 도구가 없어서 파이프라인이 docker build 를 못 한다.
# 그래서 CLI 만 넣는다 — 데몬은 호스트 것을 쓴다.
#
# jdk17 태그인 이유: 프로젝트가 Java 17 이다 (build.gradle toolchain).
FROM jenkins/jenkins:lts-jdk17

USER root

# docker CLI + compose 플러그인. 데몬(docker-ce)은 넣지 않는다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg \
      -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
 && rm -rf /var/lib/apt/lists/*

# ⚠ 소켓 권한 — 호스트의 docker 그룹 GID 와 맞춰야 한다.
#   /var/run/docker.sock 은 호스트에서 root:docker 소유다. 컨테이너 안의
#   jenkins 사용자가 그 GID 를 가진 그룹에 속해야 읽을 수 있다.
#   서버1 실측 GID = 988. 다른 서버에 올릴 때는 build arg 로 바꾼다.
#     getent group docker   →  docker:x:988:ubuntu
ARG DOCKER_GID=988
RUN if ! getent group "${DOCKER_GID}" >/dev/null; then \
      groupadd -g "${DOCKER_GID}" docker-host; \
    fi \
 && usermod -aG "${DOCKER_GID}" jenkins

USER jenkins

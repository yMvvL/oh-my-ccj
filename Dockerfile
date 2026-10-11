# 一个能跑起来的 ccj，两条命令。
#
#   docker build -t ccj .
#   docker run --rm -p 6767:6767 ccj          # 然后用 `docker logs` 里那条带 token 的地址打开
#
# 两条刻意的选择：
#
# 1. **构建阶段不跑测试。** 测试套件（869 个用例）是给 `./mvnw verify` 用的：它里面的真浏览器那条要 Chrome，
#    而把它塞进一次镜像构建，只会让「构建失败」这件事失去意义——分不清是代码坏了还是容器里缺了个程序。
#    CI 里两件事都做，而且是分开的两步。
# 2. **运行阶段不跑 root。** 这个程序以你的身份执行 shell 命令，所以它在容器里也不该是 root：一个 uid
#    10001 的用户 + 一个装着全部记忆的卷，比一个 root 容器诚实得多。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先把 pom 单独拷进来再预热依赖：源码改了不该重下整棵依赖树。`|| true` 是刻意的——这一步只是缓存预热，
# 真正的门是下面那次 package。
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
# curl 只为 HEALTHCHECK 而装（这个基础镜像里没有它），装完立刻清 apt 索引。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --create-home --uid 10001 ccj \
 && mkdir -p /home/ccj/.oh-my-ccj \
 && chown -R ccj:ccj /home/ccj
USER ccj
WORKDIR /home/ccj
COPY --from=build /build/target/ccj.jar /home/ccj/ccj.jar

# 会话、审批规则、嵌入索引、工作区注册表全在 CCJ_HOME 下，所以 `<home>` 就是「这台机器的全部记忆」：
# 挂一个具名卷（`-v ccj-home:/home/ccj/.oh-my-ccj`）它才活得比容器久，而 Docker 会用上面那个目录的属主
# 去初始化一个新卷，于是 uid 10001 写得进去。
#
# **刻意不写 `VOLUME`。** 声明出来的卷第一次创建时属于 root，而这个进程是 uid 10001——那会把自己锁在门外。
# 这条不是推理出来的：第一版就声明了它，起容器时第一行是 `error: 无法写入 …/workspaces.json`。
ENV CCJ_HOME=/home/ccj/.oh-my-ccj
EXPOSE 6767

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD curl -fsS http://127.0.0.1:6767/api/status >/dev/null || exit 1

# 默认绑 0.0.0.0：容器里的环回对宿主机没有意义，而 `-p 127.0.0.1:6767:6767` 才是把可达范围收窄的那一步。
# 绑非环回**必须**带 token，所以 ccj 会自己生成一个、存进卷里、并把带 token 的地址打在第一屏——`docker
# logs` 里那行 `?token=…` 就是这把钥匙。**把带 token 的 URL 当密码对待。**
ENTRYPOINT ["java", "-jar", "/home/ccj/ccj.jar"]
CMD ["--no-open", "--host", "0.0.0.0", "--port", "6767"]

# sub2api 通过本地 sub2api 中转访问 Codex 的配置记录（2026-05-28 更新）

## 1. 背景

- 目标地址：`https://new.sharedchat.cc/codex`
- 现象：本地可用，但服务器（`ag-middleware-new / 5.78.192.144`）直连报 Cloudflare `403 blocked`
- 根因：服务器出口 IP 被 Cloudflare 风控拦截，无法稳定直连目标域名
- 当前策略：服务器不再直连目标域名，也不再经过 Cloudflare Tunnel；服务器只通过 SSH 反向隧道访问本地 `sub2api`

## 2. 当前最终可用链路

```text
服务器 sub2api 容器
-> 172.18.0.1:18827
-> SSH 反向隧道
-> 本机 127.0.0.1:18080
-> 本地 sub2api
-> https://new.sharedchat.cc/codex
```

说明：

- `172.18.0.1` 是服务器 Docker 容器访问服务器宿主机的网关地址
- 服务器侧 `172.18.0.1:18827` 由 SSH 反向隧道监听
- 本机侧 `127.0.0.1:18080` 是本地 `sub2api` 暴露端口
- 真正访问 `https://new.sharedchat.cc/codex` 的是本地 `sub2api`，不是服务器
- 服务器 `sub2api` 账号里应填写 `http://172.18.0.1:18827`，不要再加 `/codex`

## 3. 本机改动（Mac）

### 3.1 本地 sub2api

- 容器端口映射：`127.0.0.1:18080->8080/tcp`
- 本地 `sub2api` 内部已配置上游账号：
  - `base_url`：`https://new.sharedchat.cc/codex`
  - `api_key`：对应上游可用 key

### 3.2 LaunchAgent：反向隧道自启

- 文件：`/Users/zengyue/Library/LaunchAgents/com.zengyue.aihub.reverse-tunnel.plist`
- Label：`com.zengyue.aihub.reverse-tunnel`
- 当前核心转发参数：

```bash
-R 172.18.0.1:18827:127.0.0.1:18080 root@5.78.192.144
```

- SSH key：
  - 私钥：`/Users/zengyue/.ssh/ag_middleware_tunnel_ed25519`
  - 公钥：`/Users/zengyue/.ssh/ag_middleware_tunnel_ed25519.pub`

- 日志：
  - `/Users/zengyue/Library/Logs/aihub-reverse-tunnel.log`
  - `/Users/zengyue/Library/Logs/aihub-reverse-tunnel.err.log`

### 3.3 旧本机反代

- 旧方案使用 `/Users/zengyue/bin/aihub-proxy.mjs`
- 旧监听端口：`127.0.0.1:8327`
- 旧链路：`服务器 -> 18827 -> 本机 8327 -> https://new.sharedchat.cc/codex`
- 当前新链路已经不再使用 `8327`
- `com.zengyue.aihub.proxy` 可以继续保留，但当前服务器 Codex 链路不依赖它

## 4. 服务器改动（ag-middleware-new）

### 4.1 SSHD 配置

- 文件：`/etc/ssh/sshd_config.d/60-reverse-tunnel.conf`
- 内容：

```conf
AllowTcpForwarding remote
GatewayPorts clientspecified
PermitListen 172.18.0.1:18827
```

- 已执行：
  - `sshd -t`
  - `systemctl reload ssh`

### 4.2 服务端口现状

- `sub2api` 容器映射：`127.0.0.1:8080->8080/tcp`
- 反向隧道监听：`172.18.0.1:18827`

## 5. 服务器 sub2api 账号配置

当前已更新账号：

- 账号 ID：`516`
- 账号名称：`https://new.sharedchat.cc/codex`
- `base_url`：`http://172.18.0.1:18827`
- `api_key`：本地 `sub2api` 创建的用户 API Key

注意：

- 不要再填 `http://172.18.0.1:18827/codex`
- 服务器 `sub2api` 里填的是“本地 sub2api 的用户 API Key”
- `https://new.sharedchat.cc/codex` 的上游 key 只应保存在本地 `sub2api` 的上游账号里

## 6. 验证结果（2026-05-28）

### 6.1 本机 LaunchAgent

```bash
launchctl print gui/$(id -u)/com.zengyue.aihub.reverse-tunnel
```

确认参数：

```text
-R
172.18.0.1:18827:127.0.0.1:18080
```

### 6.2 服务器容器内健康检查

```bash
docker exec sub2api sh -lc 'wget -qO- -T 8 http://172.18.0.1:18827/health'
```

返回：

```json
{"status":"ok"}
```

### 6.3 服务器容器内真实 Responses 调用

从服务器 `sub2api` 容器内请求：

```text
POST http://172.18.0.1:18827/v1/responses
```

验证结果：

- 返回：`HTTP 200`
- 响应状态：`completed`
- 输出文本：`ok`

### 6.4 服务器数据库账号检查

```bash
docker exec sub2api-postgres psql -U sub2api -d sub2api -At -F ' | ' -c \
  "select id,status,credentials->>'base_url', left(credentials->>'api_key',6) || '...' || right(credentials->>'api_key',6) from accounts where id=516;"
```

返回示例：

```text
516 | active | http://172.18.0.1:18827 | sk-5ec...e055d7
```

## 7. 日常运维命令

### 7.1 查看本机隧道状态

```bash
launchctl print gui/$(id -u)/com.zengyue.aihub.reverse-tunnel | egrep 'state =|pid ='
```

### 7.2 查看当前隧道参数

```bash
launchctl print gui/$(id -u)/com.zengyue.aihub.reverse-tunnel | sed -n '1,70p'
```

应看到：

```text
172.18.0.1:18827:127.0.0.1:18080
```

### 7.3 重启本机隧道

只重启已加载配置：

```bash
launchctl kickstart -k gui/$(id -u)/com.zengyue.aihub.reverse-tunnel
```

如果修改了 plist 里的 `ProgramArguments`，需要重新加载 LaunchAgent：

```bash
launchctl bootout gui/$(id -u) /Users/zengyue/Library/LaunchAgents/com.zengyue.aihub.reverse-tunnel.plist
launchctl bootstrap gui/$(id -u) /Users/zengyue/Library/LaunchAgents/com.zengyue.aihub.reverse-tunnel.plist
```

### 7.4 看日志

```bash
tail -f /Users/zengyue/Library/Logs/aihub-reverse-tunnel.err.log
```

### 7.5 服务器侧快速检查

```bash
ss -lntp | egrep ':18827\s'
docker exec sub2api sh -lc 'wget -qO- -T 8 http://172.18.0.1:18827/health'
```

## 8. 回滚方法

### 8.1 回滚到旧 8327 反代链路

将 `/Users/zengyue/Library/LaunchAgents/com.zengyue.aihub.reverse-tunnel.plist` 里的参数改回：

```bash
-R 172.18.0.1:18827:127.0.0.1:8327 root@5.78.192.144
```

然后重新加载 LaunchAgent：

```bash
launchctl bootout gui/$(id -u) /Users/zengyue/Library/LaunchAgents/com.zengyue.aihub.reverse-tunnel.plist
launchctl bootstrap gui/$(id -u) /Users/zengyue/Library/LaunchAgents/com.zengyue.aihub.reverse-tunnel.plist
```

服务器 `sub2api` 账号回滚为：

```text
base_url = http://172.18.0.1:18827/codex
api_key  = 上游 new.sharedchat.cc/codex 可用 key
```

### 8.2 停用隧道

```bash
launchctl bootout gui/$(id -u) /Users/zengyue/Library/LaunchAgents/com.zengyue.aihub.reverse-tunnel.plist
```

### 8.3 撤销服务器 SSHD 放行

```bash
rm -f /etc/ssh/sshd_config.d/60-reverse-tunnel.conf
sshd -t && systemctl reload ssh
```

## 9. 额外说明

- 当前方案不依赖 Cloudflare Tunnel 或 `trycloudflare.com`
- 服务器不会直连 `https://new.sharedchat.cc/codex`
- 服务器只通过 SSH 反向隧道访问本地 `sub2api`
- 最终访问 `https://new.sharedchat.cc/codex` 的是本地 `sub2api`
- 该方案依赖本机在线：如果本机关机、断网、本地 `sub2api` 停止，服务器这条账号链路会不可用
- 本地临时验证 API Key 用完后应删除或禁用，避免长期留存

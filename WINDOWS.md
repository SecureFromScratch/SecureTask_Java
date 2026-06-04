# Running SecureTask on Windows

Two paths are available. Pick the one that suits you:

| | Option A — Native Windows (no WSL) | Option B — WSL2 |
|---|---|---|
| Docker | Docker Desktop for Windows | Docker Engine inside WSL2 **or** Docker Desktop |
| Terminal | PowerShell | Bash (Ubuntu) |
| Effort | Less setup | More Linux-compatible |

---

## Option A — Native Windows (No WSL)

### Prerequisites

| Tool | Notes |
|------|-------|
| Java JDK 21 | [Eclipse Temurin](https://adoptium.net/) — add `bin\` to your PATH |
| Docker Desktop | [docker.com](https://www.docker.com/products/docker-desktop/) — Hyper-V or WSL2 engine both work |
| PowerShell 5.1+ | Built into Windows 10/11. Allow scripts once (run as Admin): `Set-ExecutionPolicy RemoteSigned` |

### 1 — Start the backing services

```powershell
docker compose up -d postgres localstack
docker compose ps   # wait until both show "healthy"
```

### 2 — Terminal 1: start the API

```powershell
.\scripts\Start-Api.ps1
```

Ready when you see: `Started SecureTaskApplication`

### 3 — Terminal 2: start the BFF

```powershell
.\scripts\Start-Bff.ps1
```

Ready when you see: `Tomcat started on port 8081`

### 4 — Open the browser

**http://localhost:8081**

Register an account — the first user becomes **ADMIN**.

### Run tests

```powershell
.\gradlew.bat :api:test
```

(Docker must be running — tests use Testcontainers for PostgreSQL.)

### Stop everything

```powershell
# Stop Spring Boot apps: Ctrl+C in each terminal

# Stop Docker services:
docker compose down
```

### Ports already in use?

```powershell
# Find and kill whatever holds a port (e.g. 8080):
Get-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess | Stop-Process -Force
Get-Process -Id (Get-NetTCPConnection -LocalPort 8081).OwningProcess | Stop-Process -Force
```

### Debugging from IntelliJ IDEA / VS Code

The API and BFF run as normal JVM processes. To enable remote debugging, add `JAVA_TOOL_OPTIONS` before launching:

```powershell
$env:JAVA_TOOL_OPTIONS = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
.\scripts\Start-Api.ps1
```

Use port `5005` for the API and `5006` for the BFF, then create a Remote JVM Debug configuration pointing at `localhost:5005` (or `5006`).

### Troubleshooting

**`\r: command not found` in LocalStack init scripts** — Windows Git converted line endings. The `.gitattributes` in this repo enforces LF on all shell scripts. If you cloned before that was in place, fix it once:
```powershell
git config --global core.autocrlf false
git rm --cached -r .
git reset --hard
```

**`gradlew.bat` not found** — regenerate the Gradle wrapper:
```powershell
gradle wrapper
```

**Slow Maven/Gradle builds** — Windows Defender can hammer build caches. Exclude the project folder and `%USERPROFILE%\.gradle` from real-time scanning.

**Long path errors** — enable long paths (run PowerShell as Admin):
```powershell
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" `
  -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force
```

---

## Option B — WSL2

The WSL2 path gives you a full Linux environment where `docker compose up` works
identically to Linux, and your Windows IDE can still attach a debugger over `localhost`.

### Prerequisites

| Tool | Notes |
|------|-------|
| WSL2 + Ubuntu | `wsl --install` in an elevated PowerShell, then reboot |
| Docker | See options below |
| IntelliJ IDEA or VS Code | Installed on Windows as normal |
| Git (inside WSL2) | `sudo apt install git` |

#### Docker: two options

**Option B1 — Docker Engine inside WSL2 (recommended)**

Free for everyone, no GUI needed:

```bash
# Inside Ubuntu (WSL2)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
# Close and reopen the terminal
docker run hello-world   # verify
```

To start Docker automatically when you open WSL2, add this to `~/.bashrc`:
```bash
if ! pgrep dockerd > /dev/null; then sudo service docker start; fi
```

**Option B2 — Docker Desktop**

Easier setup, includes a GUI. Free only for personal/educational use
(commercial use requires a paid subscription).

Download from docker.com → enable "Use WSL2 based engine" during install →
Settings → Resources → WSL Integration → toggle Ubuntu on → Apply & Restart.

### 1 — Install JDK

```bash
sudo apt install openjdk-21-jdk-headless
```

### 2 — Clone inside WSL2

Open an Ubuntu terminal and clone into the WSL2 filesystem:

```bash
cd ~
git clone <repo-url> projects/sfs/SecureTask_Java
cd projects/sfs/SecureTask_Java
```

> **Do not clone into `/mnt/c/...`** (the Windows drive). File watching is slow
> there and IDE breakpoint mapping can break.

### 3 — Start the stack

```bash
docker compose up -d
docker compose ps
```

Open **http://localhost:8082** in your Windows browser — WSL2 forwards ports automatically.

### 4 — Running without Docker (gradlew bootRun)

Follow QUICKSTART.md — the commands are bash and run inside the WSL2 Ubuntu terminal unchanged.

```bash
fuser -k 8080/tcp   # free a port if already in use
fuser -k 8081/tcp
```

### Debugging from IntelliJ IDEA

1. Run → Edit Configurations → **+** → Remote JVM Debug
2. Host = `localhost`, Port = `5005` (API) or `5006` (BFF)
3. Use module classpath → `api` or `bff`

Open the project: File → Open → `\\wsl$\Ubuntu\home\<your-user>\projects\sfs\SecureTask_Java`

### Debugging from VS Code

Add to `.vscode/launch.json`:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Attach API (Docker)",
      "request": "attach",
      "hostName": "localhost",
      "port": 5005
    },
    {
      "type": "java",
      "name": "Attach BFF (Docker)",
      "request": "attach",
      "hostName": "localhost",
      "port": 5006
    }
  ]
}
```

Open the project folder from inside WSL2 with `code .` (VS Code's Remote-WSL extension handles the rest).

### Troubleshooting (WSL2)

**`\r: command not found`** — same fix as Option A above.

**Docker Desktop not seeing WSL2 distro** — Docker Desktop → Settings → Resources → WSL Integration → toggle Ubuntu on → Apply & Restart.

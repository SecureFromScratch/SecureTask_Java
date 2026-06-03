# Running SecureTask on Windows

The recommended approach is **WSL2**. It gives you a full Linux environment where
`docker compose up` works identically to Linux, and your Windows IDE can still
attach a debugger over `localhost`.

---

## 1 — Prerequisites

| Tool | Notes |
|------|-------|
| WSL2 + Ubuntu | `wsl --install` in an elevated PowerShell, then reboot |
| Docker | See options below |
| IntelliJ IDEA or VS Code | Installed on Windows as normal |
| Git (inside WSL2) | `sudo apt install git` |

### Docker: two options

**Option A — Docker Engine inside WSL2 (recommended)**

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

**Option B — Docker Desktop**

Easier setup, includes a GUI. Free only for personal/educational use
(commercial use requires a paid subscription).

Download from docker.com → enable "Use WSL2 based engine" during install →
Settings → Resources → WSL Integration → toggle Ubuntu on → Apply & Restart.

---

## 2 — Install JDK
```bash
sudo apt install openjdk-21-jdk-headless
```

## 3 — Clone inside WSL2

Open an Ubuntu terminal and clone into the WSL2 filesystem:

```bash
cd ~
git clone <repo-url> projects/sfs/SecureTask_Java
cd projects/sfs/SecureTask_Java
```

> **Do not clone into `/mnt/c/...`** (the Windows drive). File watching is slow
> there and IDE breakpoint mapping can break.

---

## 4 — Start the stack

```bash
docker compose up -d
```

Wait for all services to be healthy:

```bash
docker compose ps
```

Then open **http://localhost:8081** in your Windows browser — WSL2 forwards
ports to Windows `localhost` automatically.

---

## 5 — Running without Docker (gradlew bootRun)

Follow the same steps as QUICKSTART.md — the commands are bash and run inside
the WSL2 Ubuntu terminal unchanged.

To kill a port that is already in use from within WSL2:

```bash
fuser -k 8080/tcp
fuser -k 8081/tcp
```

---

## 6 — Debugging from IntelliJ IDEA

The containers already expose JDWP debug ports:

| Service | Debug port |
|---------|-----------|
| API (`app`) | 5005 |
| BFF (`bff`) | 5006 |

**Create a Remote JVM Debug configuration:**

1. Run → Edit Configurations → **+** → Remote JVM Debug
2. Set **Host** = `localhost`, **Port** = `5005` (or `5006`)
3. Set **Use module classpath** to the `api` (or `bff`) module
4. Click the debug button — IntelliJ connects to the running container

**Opening the project in IntelliJ:**

- File → Open → type `\\wsl$\Ubuntu\home\<your-user>\projects\sfs\SecureTask_Java`
- IntelliJ detects the WSL2 path and indexes sources correctly
- Breakpoints map to the container sources automatically

---

## 7 — Debugging from VS Code

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

Open the project folder from inside WSL2 with `code .` (VS Code's Remote-WSL
extension handles the rest).

---

## 7 — Troubleshooting

### `\r: command not found` in LocalStack init scripts

Windows Git converted line endings to CRLF. Fix it once:

```bash
git config --global core.autocrlf false
git rm --cached -r .
git reset --hard
```

The `.gitattributes` in this repo enforces LF on all shell scripts for future
clones.

### Port already in use (Windows PowerShell fallback)

If you need to free a port from PowerShell:

```powershell
Get-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess | Stop-Process -Force
```

### Docker Desktop not seeing WSL2 distro (Option B only)

Docker Desktop → Settings → Resources → WSL Integration → toggle Ubuntu on →
Apply & Restart.

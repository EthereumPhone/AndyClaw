# Termux Setup Guide for AndyClaw

This guide walks you through installing and configuring [Termux](https://termux.dev/en/) so that AndyClaw can run Linux commands, execute scripts, and use ClawHub skills that depend on a terminal environment.

---

## 1. Install Termux

Download and install the main Termux app. The recommended source is **F-Droid** or Github

- **F-Droid:** [https://f-droid.org/en/packages/com.termux/](https://f-droid.org/en/packages/com.termux/)
- **GitHub Releases:** [https://github.com/termux/termux-app/releases](https://github.com/termux/termux-app/releases)

## (Optional) 2. Install Termux:API

The Termux:API add-on lets Termux (and by extension, AndyClaw) access Android device functions like sensors, clipboard, and notifications.

- **F-Droid:** [https://f-droid.org/en/packages/com.termux.api/](https://f-droid.org/en/packages/com.termux.api/)
- **Github Releases:** [https://github.com/termux/termux-api/releases](https://github.com/termux/termux-api/releases)

> **Important:** Install both Termux and Termux:API from the **same source** (both from F-Droid, or both from GitHub). Mixing sources will cause signature mismatches and the apps won't work together.

## 3. Grant Permissions

Open both Termux (and Termux:API) at least once. When prompted, grant the permissions they request. These are needed for Termux to function properly and for AndyClaw to communicate with it.

## 4. Setup Termux for AndyClaw

For AndyClaw to send commands to Termux, two things need to be configured:

### A. Grant the Termux permission to AndyClaw

Go to your device's **Settings > Apps > AndyClaw > Permissions** and enable the **"Run commands in Termux environment"** permission.

### B. Enable external apps (and Termux:API) inside Termux

By default, Termux blocks other apps from running commands in its environment. You need to opt in:

1. Open the **Termux** app.
2. Type the following command and press Enter:

```bash
nano .termux/termux.properties
```

1. Scroll down until you find the line:

```
# allow-external-apps = true
```

1. Remove the `#` at the beginning to uncomment it, so it reads:

```
allow-external-apps = true
```

1. Save the file by pressing the **Ctrl** button on the Termux on-screen keyboard, then pressing **S** on your keyboard.
2. Exit the editor by pressing **Ctrl** again, then pressing **X**.
3. Back in the terminal, run this command to apply the change:

```bash
termux-reload-settings
```

1. (Optional) Enable termux-api inside the terminal

```bash
apt install termux-api
```

1. (Optional) To minimize issues, run `pkg upgrade` to bring all packages up to date.

## 5. Verify It Works

Open AndyClaw and try asking the AI to run a simple command in Termux, for example: *"Run `uname -a` in Termux"*. If everything is set up correctly, the AI should execute the command and return the output.

---

## Troubleshooting

- **AndyClaw can't find Termux:** Make sure Termux is installed and has been opened at least once. Try restarting Termux completely (This means also exiting the termux session via the termux notification)
- **Permission denied errors:** Double-check that you completed both steps in section 4 — the Android permission for AndyClaw *and* the `allow-external-apps` setting inside Termux. After changing `termux.properties`, make sure you ran `termux-reload-settings`. Restart Termux if necessary.
- **Commands hang or time out:** Restart Termux/AndyClaw and try again. If the issue persists, try running the command manually in Termux first to make sure it works on its own. make sure to run `pkg upgrade` yourself at least once, since the process requires a decent amount of time on unstable internet connections. The AI agent might try to run the command on your behalf and get stuck.
- **Termux:API commands don't work:** Make sure you installed the Termux:API app from the same source as Termux. Inside Termux, run `pkg install termux-api` to install the command-line tools that interface with the API app.


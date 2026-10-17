# Directory Services Portal @VERSION@: native deployment package

The portal without containers, for a Linux host with systemd, Java 21+ and (optionally) nginx.
Built @BUILD_DATE@ from @GIT_REVISION@.

```bash
tar xzf directory-services-portal-@VERSION@-native.tar.gz
cd directory-services-portal-@VERSION@-native
sudo ./install.sh
sudo vi /etc/directory-services-portal/portal.env     # LDAP connection, administrators
sudo systemctl restart directory-services-portal
```

| Installed to | |
|---|---|
| `/opt/directory-services-portal/api` | the API (`directory-services-portal.jar`), run by systemd as user `dsp` on 127.0.0.1:3001 |
| `/opt/directory-services-portal/web` | the web client (static files) |
| `/etc/directory-services-portal/portal.env` | settings, documented; kept on upgrades |
| `/var/lib/ens/directory-portal` | the ENS license (`ENS_LICENSE_FILE`, written by the license screen), this installation's id and the license revisions seen (`ENS_LICENSE_STATE_DIR`); keep it |
| `/var/lib/directory-services-portal` | data: configuration, audit log, analysis results (back this up) |
| `/etc/nginx/conf.d/directory-services-portal.conf` | serves the client on port 8080 and proxies `/api` (when nginx is installed) |

- Logs: `journalctl -u directory-services-portal -f`.
- License: without an ENS Solutions license only sign-in and the license screen work. Sign in as
  an administrator and upload it under *Administration > License* (`/admin/license`), which also
  shows the installation id to bind a license to; or copy it in with
  `sudo install -m 640 -o dsp -g dsp acme.lic /var/lib/ens/directory-portal/license.lic`.
  The paths are `ENS_LICENSE_FILE` / `ENS_LICENSE_STATE_DIR` in `portal.env` (the unit sets the
  same defaults); the directory must stay writable by `dsp`. The startup log line `License: ...`
  says what is in effect. For a trial, download an evaluation license from the ENS licensing portal.
- HTTPS: terminate TLS in nginx (add a `listen 443 ssl` server, or put a proxy in front) and set
  `COOKIE_SECURE=true`. For X.509 sign-in through nginx (`X509_SOURCE=header`,
  `X509_TRUSTED_PROXIES=127.0.0.1/32`), see the installation guide:
  https://winllc.github.io/ens-apps-documentation/directory-services-portal/install/
- With SELinux enforcing, nginx needs `setsebool -P httpd_can_network_connect 1` to reach the API.
- Upgrade: run the new package's `install.sh`; settings and data are kept.

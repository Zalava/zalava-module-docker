# Operate Zalava

Run Zalava with persistent PostgreSQL and user-owned runtime storage. Keep logs
and private configuration outside image layers and source control.

Before upgrading, verify the immutable artifact and release notes, back up data,
replace only the application artifact, then start and perform a health check. If
health fails, restore the preceding verified artifact without replacing data.

Install official modules through the reviewed lifecycle: resolve a catalog entry,
verify release identity and checksum, review permissions, approve, install, and
restart when required. Do not copy arbitrary JARs into a running instance.

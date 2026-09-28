# Install Zalava

Zalava supports a fresh local installation; it does not import or modify an
existing assistant installation. Build from a clean public checkout with Java
25, PostgreSQL, and either a configured model provider or compatible local
model:

```bash
./gradlew :app:bootRun
```

Complete onboarding, open chat, and submit a simple request. Docker is needed
only for a published container path. Public artifacts and official modules must
be consumable without a GitHub login, token, or cached package credentials.

Keep user-owned data and private configuration outside replaceable application
artifacts.

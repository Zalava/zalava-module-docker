# Configure Zalava

Keep secrets and mutable user data outside source control and a replaceable JAR
or image. Supply provider credentials, database settings, and module credentials
through the host's private configuration mechanism. Never commit API keys,
tokens, database passwords, user prompts, or personal workspace data.

Modules receive only their declared scoped configuration and opaque secret
references; they must not read arbitrary host configuration files. Use
PostgreSQL for persistent data and retain data, private configuration, and
approved module artifacts when upgrading.

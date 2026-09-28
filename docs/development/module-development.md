# Develop a Zalava module

Develop modules in their own repository using the public template and a released Zalava Module API version. Do not bundle the API in a module JAR. Declare stable module, factory, and provider identifiers, configuration requirements, runtime compatibility, and least permissions. Missing first-use configuration must be recoverable rather than fail host startup.

Test the built artifact with the released module contract-test kit, including provider creation, configuration outcomes, operations, cleanup, metadata, permissions, and compatibility. Release an immutable JAR with a SHA-256 checksum and release-index evidence. Publishing does not install the module; users review and approve installation in the host.

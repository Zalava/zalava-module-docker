# Alpha SDK migration candidate

Core contracts use org.zalava.api, optional extensions org.zalava.api.extensions, and the kit org.zalava.api.testing. Module implementations use org.zalava.modules. Provider argument maps contain JDK JSON values; library conversion remains internal to adapters.

Alpha.6 must be published from verified merged Zalava default-branch commits before released-kit acceptance can succeed. Local candidate tests are migration evidence, not released-kit or real-host acceptance. Formatting and 90% line / 74% branch coverage remain enforced. Never release this stack head or reuse an immutable published module version.

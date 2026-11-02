package com.namanbansal.forge.update

import okhttp3.OkHttpClient

object UpdateConfig {
    const val GITHUB_OWNER_REPO = "aviato28/NB-Forge"
    const val FORGE_SITE_URL = "https://forge.nbansal28.com/update/latest.json"

    /**
     * The single switch between update sources. false = GitHub Releases API (used now);
     * true = forge.nbansal28.com endpoint (once the site is live). Ships to users via a normal update.
     */
    const val USE_FORGE_SITE = false

    fun source(client: OkHttpClient): UpdateSource =
        if (USE_FORGE_SITE) ForgeSiteSource(client, FORGE_SITE_URL) else GitHubReleasesSource(client, GITHUB_OWNER_REPO)
}

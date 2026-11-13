package com.namanbansal.forge.update

import okhttp3.OkHttpClient

object UpdateConfig {
    const val GITHUB_OWNER_REPO = "aviato28/NB-Forge"
    const val FORGE_SITE_URL = "https://forge.nbansal28.com/update/latest.json"

    /**
     * The single switch between update sources. true = forge.nbansal28.com (live since 2026-11-13),
     * falling back to the GitHub Releases API if the site fails; false = GitHub Releases only.
     */
    const val USE_FORGE_SITE = true

    fun source(client: OkHttpClient): UpdateSource =
        if (USE_FORGE_SITE) {
            FallbackSource(ForgeSiteSource(client, FORGE_SITE_URL), GitHubReleasesSource(client, GITHUB_OWNER_REPO))
        } else {
            GitHubReleasesSource(client, GITHUB_OWNER_REPO)
        }
}

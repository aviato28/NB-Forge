package com.namanbansal.forge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class GitHubReleasesSourceTest {

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: String = apkAsset(tag.removePrefix("v")),
        body: String = "- Fix things",
    ) = """
        {
          "url": "https://api.github.com/repos/aviato28/NB-Forge/releases/1",
          "tag_name": "$tag",
          "name": "Forge ${tag.removePrefix("v")}",
          "draft": $draft,
          "prerelease": $prerelease,
          "created_at": "2026-11-01T10:00:00Z",
          "published_at": "2026-11-02T10:00:00Z",
          "body": "$body",
          "author": {"login": "github-actions[bot]"},
          "assets": $assets
        }
    """.trimIndent()

    private fun apkAsset(v: String, digest: String? = "sha256:" + "AB".repeat(32)) = """
        [
          {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt", "size": 10},
          {"name": "forge-$v.apk", "content_type": "application/vnd.android.package-archive",
           "browser_download_url": "https://github.com/aviato28/NB-Forge/releases/download/v$v/forge-$v.apk",
           "size": 7340032 ${if (digest != null) ", \"digest\": \"$digest\"" else ""}}
        ]
    """.trimIndent()

    @Test fun mapsStableRelease() {
        val info = GitHubReleasesSource.parseRelease(release("v0.1.104"))!!
        assertEquals("0.1.104", info.version)
        assertEquals(104, info.versionCode)
        assertEquals(Channel.Stable, info.channel)
        assertEquals("- Fix things", info.notes)
        assertEquals("2026-11-02T10:00:00Z", info.publishedAt)
        assertEquals("https://github.com/aviato28/NB-Forge/releases/download/v0.1.104/forge-0.1.104.apk", info.apkUrl)
        assertEquals(7340032L, info.size)
        assertEquals("ab".repeat(32), info.sha256)
    }

    @Test fun missingDigestGivesNullSha() {
        val info = GitHubReleasesSource.parseRelease(release("v0.1.104", assets = apkAsset("0.1.104", digest = null)))!!
        assertNull(info.sha256)
    }

    @Test fun releaseWithoutApkIsIgnored() {
        assertNull(GitHubReleasesSource.parseRelease(release("v0.1.104", assets = "[]")))
    }

    @Test fun badTagIsIgnored() {
        assertNull(GitHubReleasesSource.parseRelease(release("nightly")))
    }

    @Test fun betaPicksNewestIncludingPrereleases() {
        val list = "[" + listOf(
            release("v0.1.103"),
            release("v0.1.106", prerelease = true),
            release("v0.1.107", draft = true),
            release("v0.1.105"),
        ).joinToString(",") + "]"
        val info = GitHubReleasesSource.parseReleaseList(list)!!
        assertEquals(106, info.versionCode)
        assertEquals(Channel.Beta, info.channel)
    }

    @Test fun betaFallsBackToStableWhenNewer() {
        val list = "[" + listOf(release("v0.1.110"), release("v0.1.108", prerelease = true)).joinToString(",") + "]"
        val info = GitHubReleasesSource.parseReleaseList(list)!!
        assertEquals(110, info.versionCode)
        assertEquals(Channel.Stable, info.channel)
    }

    @Test fun emptyListGivesNull() {
        assertNull(GitHubReleasesSource.parseReleaseList("[]"))
    }

    @Test fun garbageThrowsParseError() {
        try {
            GitHubReleasesSource.parseRelease("<html>oops</html>")
            fail("expected UpdateException")
        } catch (e: UpdateException) {
            assertEquals(UpdateException.Kind.Parse, e.kind)
        }
    }
}

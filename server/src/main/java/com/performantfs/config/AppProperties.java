package com.performantfs.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Application settings, bound from {@code pfs.*} properties (or {@code PFS_*} environment
 * variables). Every value has a default, so a bare {@code ./gradlew bootRun} works.
 *
 * @param dataDir           root of blob storage; tmp/ and blobs/ live under it so a finished
 *                          upload is moved into place with an atomic rename on one filesystem
 * @param chunkSize         size of each chunk in the resumable upload protocol
 * @param fastPathMax       uploads at or under this size may use the single-request path
 * @param maxFileSize       largest file accepted
 * @param defaultQuota      storage given to a new account
 * @param uploadTtl         an upload session idle this long expires
 * @param sessionTtl        lifetime of a sign-in session
 * @param trashRetention    trashed items are purged after this long
 * @param maintenanceInterval pause between background maintenance runs
 * @param indexInterval     how often the content indexer looks for queued files it wasn't woken
 *                          for (uploads wake it straight away; this catches crashes and backlogs)
 * @param secureCookies     mark the session cookie Secure (on in production, behind HTTPS)
 * @param webDir            the built web app to serve (web/dist); unset in development, where Vite serves it
 */
@ConfigurationProperties("pfs")
public record AppProperties(
        @DefaultValue(".data") Path dataDir,
        @DefaultValue("8MB") DataSize chunkSize,
        @DefaultValue("8MB") DataSize fastPathMax,
        @DefaultValue("10GB") DataSize maxFileSize,
        @DefaultValue("2GB") DataSize defaultQuota,
        @DefaultValue("24h") Duration uploadTtl,
        @DefaultValue("30d") Duration sessionTtl,
        @DefaultValue("30d") Duration trashRetention,
        @DefaultValue("1m") Duration maintenanceInterval,
        @DefaultValue("30s") Duration indexInterval,
        @DefaultValue("false") boolean secureCookies,
        Path webDir) {}

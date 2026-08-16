/**
 * Caching layer for the YouTube Data API v3.
 *
 * The default YouTube Data API quota is 10,000 units/day and a single
 * `search.list` call costs 100 units — that is only ~100 searches per day for
 * the whole server. Caching identical queries is therefore a requirement, not
 * an optimisation.
 *
 * Backed by Upstash Redis over HTTP (`@upstash/redis`). When the Upstash
 * environment variables are absent the cache degrades to a no-op pass-through
 * so local development and forks keep working unchanged. Every Redis
 * interaction is wrapped in try/catch: a cache failure must never fail a
 * request.
 */

'use strict';

const { createHash } = require('crypto');
const { logger } = require('./logger');

/** Namespace for cached `search.list` responses. */
const SEARCH_NAMESPACE = 'yt:search:v1';
/** Namespace for cached `videos.list` responses. */
const VIDEO_NAMESPACE = 'yt:video:v1';

/** Default TTL for search results (24 hours) — music search results are stable. */
const DEFAULT_SEARCH_TTL_SECONDS = 86400;
/** TTL for single-video metadata (7 days) — titles change very rarely. */
const VIDEO_TTL_SECONDS = 604800;

/**
 * How much longer than its fresh TTL an entry is physically retained so it can
 * still be served as *stale* when YouTube is unavailable (`stale-if-error`).
 */
const STALE_TTL_MULTIPLIER = 7;

/** Quota units billed by the YouTube Data API per endpoint. */
const QUOTA_UNITS = { search: 100, videos: 1 };

/** Default daily quota of a YouTube Data API project. */
const DEFAULT_DAILY_QUOTA_UNITS = 10000;

/** Fraction of the daily quota at which a warning is logged. */
const QUOTA_WARN_RATIO = 0.8;

/**
 * Error thrown when the YouTube Data API rejects a call because the daily
 * quota is exhausted (HTTP 403, reason `quotaExceeded`).
 */
class QuotaExceededError extends Error {
  constructor(message = 'YouTube API daily quota exceeded') {
    super(message);
    this.name = 'QuotaExceededError';
    this.code = 'QUOTA_EXCEEDED';
  }
}

/**
 * Normalises a search query so that trivially different spellings map onto a
 * single cache entry: `"Daft Punk"`, `"daft  punk "` and `"DAFT PUNK"` all
 * become `"daft punk"`.
 *
 * @param {unknown} query
 * @returns {string}
 */
function normalizeQuery(query) {
  return String(query ?? '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, ' ');
}

/**
 * @param {string} query
 * @returns {string} Namespaced cache key for a search query.
 */
function searchCacheKey(query) {
  const hash = createHash('sha256').update(normalizeQuery(query)).digest('hex');
  return `${SEARCH_NAMESPACE}:${hash}`;
}

/**
 * @param {string} videoId
 * @returns {string} Namespaced cache key for a video-metadata lookup.
 */
function videoCacheKey(videoId) {
  return `${VIDEO_NAMESPACE}:${videoId}`;
}

/**
 * Resolves the configured search TTL, falling back to 24 hours when
 * `YOUTUBE_CACHE_TTL_SECONDS` is unset or invalid.
 *
 * @returns {number}
 */
function searchTtlSeconds() {
  const parsed = parseInt(process.env.YOUTUBE_CACHE_TTL_SECONDS ?? '', 10);
  if (Number.isNaN(parsed) || parsed <= 0) return DEFAULT_SEARCH_TTL_SECONDS;
  return parsed;
}

/**
 * Resolves the daily quota used for the observability counters.
 *
 * @returns {number}
 */
function dailyQuotaUnits() {
  const parsed = parseInt(process.env.YOUTUBE_DAILY_QUOTA_UNITS ?? '', 10);
  if (Number.isNaN(parsed) || parsed <= 0) return DEFAULT_DAILY_QUOTA_UNITS;
  return parsed;
}

/**
 * @typedef {object} CacheResult
 * @property {unknown} value  - The cached or freshly fetched payload.
 * @property {'hit'|'miss'|'stale'} source - Where the payload came from.
 */

/**
 * Creates the YouTube cache.
 *
 * @param {object} [options]
 * @param {{ get: Function, set: Function }} [options.redis]
 *   Redis client override (used by tests). When omitted a client is created
 *   from `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`; if those are
 *   absent the cache becomes a no-op pass-through.
 * @returns {object}
 */
function createYouTubeCache(options = {}) {
  let redis = options.redis ?? null;

  if (!redis) {
    const url = process.env.UPSTASH_REDIS_REST_URL;
    const token = process.env.UPSTASH_REDIS_REST_TOKEN;
    if (url && token) {
      const { Redis } = require('@upstash/redis');
      redis = new Redis({ url, token });
      logger.info('youtube cache enabled (upstash redis)');
    } else {
      logger.info('youtube cache disabled — UPSTASH_REDIS_REST_* not configured');
    }
  }

  const enabled = Boolean(redis);
  /** @type {Map<string, Promise<CacheResult>>} in-flight upstream requests. */
  const inFlight = new Map();

  const metrics = {
    hits: 0,
    misses: 0,
    staleServes: 0,
    errors: 0,
    coalesced: 0,
    quotaUnitsToday: 0,
    quotaDate: currentQuotaDate(),
    quotaWarned: false,
  };

  function currentQuotaDate() {
    // YouTube quota resets at midnight Pacific Time; UTC day boundaries are a
    // close enough approximation for an advisory counter.
    return new Date().toISOString().slice(0, 10);
  }

  function rollQuotaDay() {
    const today = currentQuotaDate();
    if (metrics.quotaDate !== today) {
      metrics.quotaDate = today;
      metrics.quotaUnitsToday = 0;
      metrics.quotaWarned = false;
    }
  }

  /**
   * Adds `units` to today's estimated quota consumption and warns once when
   * the configured warning threshold is crossed.
   * @param {number} units
   */
  function recordQuotaUsage(units) {
    rollQuotaDay();
    metrics.quotaUnitsToday += units;
    const limit = dailyQuotaUnits();
    if (!metrics.quotaWarned && metrics.quotaUnitsToday >= limit * QUOTA_WARN_RATIO) {
      metrics.quotaWarned = true;
      logger.warn('youtube quota usage above warning threshold', {
        quotaUnitsToday: metrics.quotaUnitsToday,
        quotaLimit: limit,
        thresholdPercent: QUOTA_WARN_RATIO * 100,
      });
    }
  }

  /**
   * Reads an envelope from Redis. Never throws.
   * @param {string} key
   * @returns {Promise<{ value: unknown, freshUntil: number }|null>}
   */
  async function readCache(key) {
    if (!enabled) return null;
    try {
      const raw = await redis.get(key);
      if (raw === null || raw === undefined) return null;
      const envelope = typeof raw === 'string' ? JSON.parse(raw) : raw;
      if (!envelope || typeof envelope !== 'object' || envelope.value === undefined) return null;
      return {
        value: envelope.value,
        freshUntil: typeof envelope.freshUntil === 'number' ? envelope.freshUntil : 0,
      };
    } catch (err) {
      metrics.errors += 1;
      logger.warn('youtube cache read failed', { key, err });
      return null;
    }
  }

  /**
   * Writes an envelope to Redis with a stale window beyond the fresh TTL.
   * Never throws.
   * @param {string} key
   * @param {unknown} value
   * @param {number} ttlSeconds
   * @returns {Promise<void>}
   */
  async function writeCache(key, value, ttlSeconds) {
    if (!enabled) return;
    try {
      const envelope = { value, freshUntil: Date.now() + ttlSeconds * 1000 };
      await redis.set(key, JSON.stringify(envelope), {
        ex: Math.ceil(ttlSeconds * STALE_TTL_MULTIPLIER),
      });
    } catch (err) {
      metrics.errors += 1;
      logger.warn('youtube cache write failed', { key, err });
    }
  }

  /**
   * Returns the cached payload for `key` when fresh, otherwise calls `fetcher`
   * exactly once per key even under concurrent requests. If the upstream call
   * fails and a stale entry exists, the stale entry is served instead
   * (`stale-if-error`).
   *
   * @param {object} params
   * @param {string} params.key          - Namespaced cache key.
   * @param {number} params.ttlSeconds   - Fresh TTL for the entry.
   * @param {number} params.quotaUnits   - Quota units billed by the upstream call.
   * @param {() => Promise<unknown>} params.fetcher - Upstream API call.
   * @returns {Promise<CacheResult>}
   */
  async function fetchWithCache({ key, ttlSeconds, quotaUnits, fetcher }) {
    const cached = await readCache(key);
    if (cached && cached.freshUntil > Date.now()) {
      metrics.hits += 1;
      return { value: cached.value, source: 'hit' };
    }

    metrics.misses += 1;

    const pending = inFlight.get(key);
    if (pending) {
      metrics.coalesced += 1;
      return pending;
    }

    const promise = (async () => {
      try {
        const value = await fetcher();
        recordQuotaUsage(quotaUnits);
        await writeCache(key, value, ttlSeconds);
        return { value, source: 'miss' };
      } catch (err) {
        if (err instanceof QuotaExceededError) recordQuotaUsage(quotaUnits);
        if (cached) {
          metrics.staleServes += 1;
          logger.warn('serving stale youtube cache entry', { key, reason: err.message });
          return { value: cached.value, source: 'stale' };
        }
        throw err;
      } finally {
        inFlight.delete(key);
      }
    })();

    inFlight.set(key, promise);
    return promise;
  }

  return {
    enabled,
    fetchWithCache,
    recordQuotaUsage,

    /**
     * Snapshot of cache/quota counters for the metrics endpoint.
     * @returns {object}
     */
    getMetrics() {
      rollQuotaDay();
      const limit = dailyQuotaUnits();
      const lookups = metrics.hits + metrics.misses;
      return {
        enabled,
        hits: metrics.hits,
        misses: metrics.misses,
        staleServes: metrics.staleServes,
        coalesced: metrics.coalesced,
        errors: metrics.errors,
        hitRate: lookups === 0 ? 0 : Number((metrics.hits / lookups).toFixed(4)),
        quotaDate: metrics.quotaDate,
        quotaUnitsToday: metrics.quotaUnitsToday,
        quotaLimit: limit,
        quotaPercentUsed: Number(((metrics.quotaUnitsToday / limit) * 100).toFixed(2)),
        searchTtlSeconds: searchTtlSeconds(),
        videoTtlSeconds: VIDEO_TTL_SECONDS,
      };
    },
  };
}

module.exports = {
  createYouTubeCache,
  normalizeQuery,
  searchCacheKey,
  videoCacheKey,
  searchTtlSeconds,
  QuotaExceededError,
  QUOTA_UNITS,
  SEARCH_NAMESPACE,
  VIDEO_NAMESPACE,
  DEFAULT_SEARCH_TTL_SECONDS,
  VIDEO_TTL_SECONDS,
  STALE_TTL_MULTIPLIER,
};

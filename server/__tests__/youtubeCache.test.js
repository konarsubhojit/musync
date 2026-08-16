'use strict';

const request = require('supertest');
const { createApp } = require('../src/server');
const {
  createYouTubeCache,
  normalizeQuery,
  searchCacheKey,
  QuotaExceededError,
} = require('../src/youtubeCache');

/** Minimal in-memory stand-in for the Upstash REST client. */
function createFakeRedis() {
  const store = new Map();
  return {
    store,
    get: jest.fn(async (key) => (store.has(key) ? store.get(key) : null)),
    set: jest.fn(async (key, value) => {
      store.set(key, value);
    }),
  };
}

/** Builds a cache envelope whose fresh window has already elapsed. */
function staleEnvelope(value) {
  return JSON.stringify({ value, freshUntil: Date.now() - 1000 });
}

describe('youtubeCache', () => {
  describe('key normalization', () => {
    it('collapses case, padding and whitespace runs', () => {
      expect(normalizeQuery('  Daft   Punk ')).toBe('daft punk');
      expect(normalizeQuery('DAFT PUNK')).toBe('daft punk');
    });

    it('maps equivalent queries onto a single cache key', () => {
      const keys = new Set([
        searchCacheKey('Daft Punk'),
        searchCacheKey('daft  punk '),
        searchCacheKey('DAFT PUNK'),
      ]);
      expect(keys.size).toBe(1);
    });

    it('gives different queries different keys', () => {
      expect(searchCacheKey('daft punk')).not.toBe(searchCacheKey('justice'));
    });
  });

  describe('no-op behaviour without Upstash env vars', () => {
    const originalUrl = process.env.UPSTASH_REDIS_REST_URL;
    const originalToken = process.env.UPSTASH_REDIS_REST_TOKEN;

    beforeEach(() => {
      delete process.env.UPSTASH_REDIS_REST_URL;
      delete process.env.UPSTASH_REDIS_REST_TOKEN;
    });

    afterEach(() => {
      if (originalUrl === undefined) delete process.env.UPSTASH_REDIS_REST_URL;
      else process.env.UPSTASH_REDIS_REST_URL = originalUrl;
      if (originalToken === undefined) delete process.env.UPSTASH_REDIS_REST_TOKEN;
      else process.env.UPSTASH_REDIS_REST_TOKEN = originalToken;
    });

    it('passes every call through to the fetcher', async () => {
      const cache = createYouTubeCache();
      expect(cache.enabled).toBe(false);

      const fetcher = jest.fn().mockResolvedValue({ items: [] });
      const params = { key: 'yt:search:v1:x', ttlSeconds: 60, quotaUnits: 100, fetcher };

      const first = await cache.fetchWithCache(params);
      const second = await cache.fetchWithCache(params);

      expect(first.source).toBe('miss');
      expect(second.source).toBe('miss');
      expect(fetcher).toHaveBeenCalledTimes(2);
      expect(cache.getMetrics().quotaUnitsToday).toBe(200);
    });
  });

  describe('with a Redis backend', () => {
    it('serves the second identical call from cache', async () => {
      const cache = createYouTubeCache({ redis: createFakeRedis() });
      const fetcher = jest.fn().mockResolvedValue({ items: [{ videoId: 'abc' }] });
      const params = { key: 'yt:search:v1:hit', ttlSeconds: 60, quotaUnits: 100, fetcher };

      const first = await cache.fetchWithCache(params);
      const second = await cache.fetchWithCache(params);

      expect(first.source).toBe('miss');
      expect(second.source).toBe('hit');
      expect(second.value).toEqual({ items: [{ videoId: 'abc' }] });
      expect(fetcher).toHaveBeenCalledTimes(1);

      const metrics = cache.getMetrics();
      expect(metrics).toMatchObject({ enabled: true, hits: 1, misses: 1, quotaUnitsToday: 100 });
    });

    it('coalesces concurrent identical lookups into one upstream call', async () => {
      const cache = createYouTubeCache({ redis: createFakeRedis() });
      let release;
      const fetcher = jest.fn(
        () => new Promise((resolve) => {
          release = () => resolve({ items: [] });
        }),
      );
      const params = { key: 'yt:search:v1:coalesce', ttlSeconds: 60, quotaUnits: 100, fetcher };

      const pending = [
        cache.fetchWithCache(params),
        cache.fetchWithCache(params),
        cache.fetchWithCache(params),
      ];
      // Let the first lookup register itself before releasing the fetcher.
      await new Promise((resolve) => setTimeout(resolve, 0));
      release();
      const results = await Promise.all(pending);

      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(results.every((r) => r.value)).toBe(true);
      expect(cache.getMetrics().coalesced).toBe(2);
      expect(cache.getMetrics().quotaUnitsToday).toBe(100);
    });

    it('serves a stale entry when the upstream call fails', async () => {
      const redis = createFakeRedis();
      redis.store.set('yt:search:v1:stale', staleEnvelope({ items: [{ videoId: 'old' }] }));
      const cache = createYouTubeCache({ redis });

      const result = await cache.fetchWithCache({
        key: 'yt:search:v1:stale',
        ttlSeconds: 60,
        quotaUnits: 100,
        fetcher: jest.fn().mockRejectedValue(new QuotaExceededError()),
      });

      expect(result.source).toBe('stale');
      expect(result.value).toEqual({ items: [{ videoId: 'old' }] });
      expect(cache.getMetrics().staleServes).toBe(1);
    });

    it('rethrows when the upstream call fails and nothing is cached', async () => {
      const cache = createYouTubeCache({ redis: createFakeRedis() });
      await expect(
        cache.fetchWithCache({
          key: 'yt:search:v1:cold',
          ttlSeconds: 60,
          quotaUnits: 100,
          fetcher: jest.fn().mockRejectedValue(new QuotaExceededError()),
        }),
      ).rejects.toBeInstanceOf(QuotaExceededError);
    });

    it('falls through to the fetcher when Redis reads throw', async () => {
      const redis = createFakeRedis();
      redis.get.mockRejectedValue(new Error('upstash down'));
      redis.set.mockRejectedValue(new Error('upstash down'));
      const cache = createYouTubeCache({ redis });

      const result = await cache.fetchWithCache({
        key: 'yt:search:v1:broken',
        ttlSeconds: 60,
        quotaUnits: 100,
        fetcher: jest.fn().mockResolvedValue({ items: [] }),
      });

      expect(result.source).toBe('miss');
      expect(cache.getMetrics().errors).toBe(2);
    });
  });
});

describe('YouTube routes with caching enabled', () => {
  const originalApiKey = process.env.YOUTUBE_API_KEY;
  const originalFetch = global.fetch;
  let app, redis;

  beforeEach(() => {
    process.env.YOUTUBE_API_KEY = 'fake-key';
    redis = createFakeRedis();
    ({ app } = createApp({ youtubeCache: createYouTubeCache({ redis }) }));
  });

  afterEach(() => {
    if (originalApiKey === undefined) delete process.env.YOUTUBE_API_KEY;
    else process.env.YOUTUBE_API_KEY = originalApiKey;
    global.fetch = originalFetch;
  });

  function mockSearchResponse() {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        items: [
          {
            id: { videoId: 'abc123' },
            snippet: {
              title: 'One More Time',
              channelTitle: 'Daft Punk',
              thumbnails: { medium: { url: 'https://example.com/thumb.jpg' } },
            },
          },
        ],
      }),
    });
  }

  it('serves equivalent queries from a single cache entry', async () => {
    mockSearchResponse();

    const first = await request(app).get('/api/youtube/search?q=Daft%20Punk');
    const second = await request(app).get('/api/youtube/search?q=daft%20%20punk%20');
    const third = await request(app).get('/api/youtube/search?q=DAFT%20PUNK');

    expect([first.status, second.status, third.status]).toEqual([200, 200, 200]);
    expect(third.body.items[0].videoId).toBe('abc123');
    expect(global.fetch).toHaveBeenCalledTimes(1);
    expect(redis.store.size).toBe(1);
  });

  it('returns 429 with a quota_exceeded code when the quota is exhausted', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ error: { errors: [{ reason: 'quotaExceeded' }] } }),
    });

    const res = await request(app).get('/api/youtube/search?q=quota');
    expect(res.status).toBe(429);
    expect(res.body.code).toBe('quota_exceeded');
  });

  it('serves stale results instead of failing when the quota is exhausted', async () => {
    redis.store.set(
      searchCacheKey('stale song'),
      staleEnvelope({ items: [{ videoId: 'old', title: 'Old', channelTitle: 'C', thumbnailUrl: '' }] }),
    );
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ error: { errors: [{ reason: 'quotaExceeded' }] } }),
    });

    const res = await request(app).get('/api/youtube/search?q=Stale%20Song');
    expect(res.status).toBe(200);
    expect(res.body.stale).toBe(true);
    expect(res.body.items[0].videoId).toBe('old');
  });

  it('caches video metadata under the video namespace', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ items: [{ snippet: { title: 'Me at the zoo', channelTitle: 'jawed' } }] }),
    });

    const res = await request(app).get('/api/youtube/video-info/jNQXAC9IVRw');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      videoId: 'jNQXAC9IVRw',
      title: 'Me at the zoo',
      channelTitle: 'jawed',
    });
    expect([...redis.store.keys()]).toEqual(['yt:video:v1:jNQXAC9IVRw']);
  });

  it('exposes cache and quota counters on GET /metrics/youtube', async () => {
    mockSearchResponse();
    await request(app).get('/api/youtube/search?q=metrics');
    await request(app).get('/api/youtube/search?q=metrics');

    const res = await request(app).get('/metrics/youtube');
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      enabled: true,
      hits: 1,
      misses: 1,
      quotaUnitsToday: 100,
      quotaLimit: 10000,
    });
  });
});

'use strict';

const request = require('supertest');
const { io: ioc } = require('socket.io-client');
const { createApp } = require('../src/server');

describe('MuSync server', () => {
  let app, httpServer, io, serverUrl;

  beforeAll((done) => {
    ({ app, httpServer, io } = createApp());
    httpServer.listen(0, () => {
      const { port } = httpServer.address();
      serverUrl = `http://localhost:${port}`;
      done();
    });
  });

  afterAll((done) => {
    io.close(done);
  });

  // ── health check ──────────────────────────────────────────────────────────
  describe('GET /health', () => {
    it('returns 200 with { status: "ok" }', async () => {
      const res = await request(app).get('/health');
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ status: 'ok' });
    });
  });

  // ── invite-link landing page ──────────────────────────────────────────────
  describe('GET /room/:roomId', () => {
    it('returns 200 with an HTML page that mentions the roomId', async () => {
      const res = await request(app).get('/room/abc-123');
      expect(res.status).toBe(200);
      expect(res.headers['content-type']).toMatch(/text\/html/);
      expect(res.text).toContain('abc-123');
      expect(res.text).toContain('MuSync');
    });

    it('rejects roomIds that contain unsafe characters', async () => {
      const res = await request(app).get('/room/' + encodeURIComponent('<script>'));
      expect(res.status).toBe(400);
      expect(res.text).not.toContain('<script>');
    });
  });

  // ── Android App Links manifest ────────────────────────────────────────────
  describe('GET /.well-known/assetlinks.json', () => {
    const originalPackage = process.env.ANDROID_APP_PACKAGE_NAME;
    const originalFingerprints = process.env.ANDROID_APP_SHA256_FINGERPRINTS;

    afterEach(() => {
      if (originalPackage === undefined) delete process.env.ANDROID_APP_PACKAGE_NAME;
      else process.env.ANDROID_APP_PACKAGE_NAME = originalPackage;
      if (originalFingerprints === undefined) delete process.env.ANDROID_APP_SHA256_FINGERPRINTS;
      else process.env.ANDROID_APP_SHA256_FINGERPRINTS = originalFingerprints;
    });

    it('returns 404 when no fingerprints are configured', async () => {
      delete process.env.ANDROID_APP_SHA256_FINGERPRINTS;
      const res = await request(app).get('/.well-known/assetlinks.json');
      expect(res.status).toBe(404);
    });

    it('returns a valid manifest when fingerprints are configured', async () => {
      process.env.ANDROID_APP_PACKAGE_NAME = 'com.example.test';
      process.env.ANDROID_APP_SHA256_FINGERPRINTS = 'AA:BB:CC, 11:22:33';
      const res = await request(app).get('/.well-known/assetlinks.json');
      expect(res.status).toBe(200);
      expect(Array.isArray(res.body)).toBe(true);
      expect(res.body[0].relation).toEqual(['delegate_permission/common.handle_all_urls']);
      expect(res.body[0].target).toEqual({
        namespace: 'android_app',
        package_name: 'com.example.test',
        sha256_cert_fingerprints: ['AA:BB:CC', '11:22:33'],
      });
    });
  });

  // ── YouTube search proxy ──────────────────────────────────────────────────
  describe('GET /api/youtube/search', () => {
    const originalApiKey = process.env.YOUTUBE_API_KEY;
    const originalFetch = global.fetch;

    afterEach(() => {
      if (originalApiKey === undefined) delete process.env.YOUTUBE_API_KEY;
      else process.env.YOUTUBE_API_KEY = originalApiKey;
      // Restore the global fetch if it was replaced
      global.fetch = originalFetch;
    });

    it('returns 503 when YOUTUBE_API_KEY is not configured', async () => {
      delete process.env.YOUTUBE_API_KEY;
      const res = await request(app).get('/api/youtube/search?q=test');
      expect(res.status).toBe(503);
      expect(res.body).toMatchObject({ error: expect.any(String) });
    });

    it('returns 400 when no query parameter is provided', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      const res = await request(app).get('/api/youtube/search');
      expect(res.status).toBe(400);
      expect(res.body).toMatchObject({ error: expect.any(String) });
    });

    it('returns 400 when the query parameter is blank', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      const res = await request(app).get('/api/youtube/search?q=');
      expect(res.status).toBe(400);
    });

    it('returns 400 when the query exceeds 200 characters', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      const longQuery = 'a'.repeat(201);
      const res = await request(app).get(`/api/youtube/search?q=${longQuery}`);
      expect(res.status).toBe(400);
    });

    it('returns 504 when the YouTube API request times out', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      global.fetch = jest.fn().mockRejectedValue(Object.assign(new Error('abort'), { name: 'AbortError' }));
      const res = await request(app).get('/api/youtube/search?q=test');
      expect(res.status).toBe(504);
    });

    it('returns mapped items from the YouTube API response', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      global.fetch = jest.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          items: [
            {
              id: { videoId: 'abc123' },
              snippet: {
                title: 'Test Video',
                channelTitle: 'Test Channel',
                thumbnails: {
                  medium: { url: 'https://example.com/thumb.jpg' },
                },
              },
            },
          ],
        }),
      });
      const res = await request(app).get('/api/youtube/search?q=test+song');
      expect(res.status).toBe(200);
      expect(res.body.items).toHaveLength(1);
      expect(res.body.items[0]).toEqual({
        videoId: 'abc123',
        title: 'Test Video',
        channelTitle: 'Test Channel',
        thumbnailUrl: 'https://example.com/thumb.jpg',
      });
      expect(global.fetch).toHaveBeenCalledWith(
        expect.stringContaining('q=test+song'),
        expect.objectContaining({ signal: expect.any(Object) }),
      );
    });

    it('returns 502 when the YouTube API responds with an error status', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      global.fetch = jest.fn().mockResolvedValue({ ok: false, status: 403 });
      const res = await request(app).get('/api/youtube/search?q=test');
      expect(res.status).toBe(502);
    });

    it('returns 500 when fetch throws', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      global.fetch = jest.fn().mockRejectedValue(new Error('network error'));
      const res = await request(app).get('/api/youtube/search?q=test');
      expect(res.status).toBe(500);
    });

    it('falls back to default thumbnail when medium thumbnail is absent', async () => {
      process.env.YOUTUBE_API_KEY = 'fake-key';
      global.fetch = jest.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          items: [
            {
              id: { videoId: 'xyz' },
              snippet: {
                title: 'No Medium',
                channelTitle: 'Channel',
                thumbnails: { default: { url: 'https://example.com/default.jpg' } },
              },
            },
          ],
        }),
      });
      const res = await request(app).get('/api/youtube/search?q=fallback');
      expect(res.status).toBe(200);
      expect(res.body.items[0].thumbnailUrl).toBe('https://example.com/default.jpg');
    });
  });

  // ── Socket.IO helpers ─────────────────────────────────────────────────────
  function connect() {
    return new Promise((resolve) => {
      const socket = ioc(serverUrl, { forceNew: true });
      socket.on('connect', () => resolve(socket));
    });
  }

  function once(socket, event) {
    return new Promise((resolve) => socket.once(event, resolve));
  }

  /** Join a room and wait for the server acknowledgement before resolving. */
  function joinRoom(socket, roomId, displayName) {
    return new Promise((resolve, reject) => {
      const payload = displayName !== undefined ? { roomId, displayName } : roomId;
      socket.emit('join_room', payload, (ack) => {
        if (ack && ack.error) reject(new Error(ack.error));
        else resolve(ack);
      });
    });
  }

  /** Leave a room and wait for the server acknowledgement before resolving. */
  function leaveRoom(socket, roomId) {
    return new Promise((resolve, reject) => {
      socket.emit('leave_room', roomId, (ack) => {
        if (ack && ack.error) reject(new Error(ack.error));
        else resolve(ack);
      });
    });
  }

  // ── join_room ─────────────────────────────────────────────────────────────
  describe('join_room', () => {
    it('notifies existing peers when a new client joins', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      // Alice joins and waits for server ack before bob joins.
      await joinRoom(alice, 'room-1');

      const peerJoined = once(alice, 'peer_joined');
      await joinRoom(bob, 'room-1');

      const payload = await peerJoined;
      expect(payload).toMatchObject({ socketId: bob.id });

      alice.disconnect();
      bob.disconnect();
    });

    it('includes memberCount in join ack', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      const aliceAck = await joinRoom(alice, 'room-membercount');
      expect(aliceAck.memberCount).toBe(1);

      const bobAck = await joinRoom(bob, 'room-membercount');
      expect(bobAck.memberCount).toBe(2);

      alice.disconnect();
      bob.disconnect();
    });

    it('ignores join_room with an empty roomId', async () => {
      const alice = await connect();

      // Server should ack with an error and NOT add alice to any room.
      const ack = await new Promise((resolve) => {
        alice.emit('join_room', '', resolve);
      });
      expect(ack).toMatchObject({ error: expect.any(String) });

      expect(alice.connected).toBe(true);
      alice.disconnect();
    });

    it('accepts join_room with { roomId, displayName } object payload', async () => {
      const alice = await connect();
      const ack = await joinRoom(alice, 'room-obj', 'Alice');
      expect(ack).toMatchObject({ ok: true });
      alice.disconnect();
    });

    it('broadcasts displayName in peer_joined when joining with object payload', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-dn');

      const peerJoined = once(alice, 'peer_joined');
      await joinRoom(bob, 'room-dn', 'Bob');

      const payload = await peerJoined;
      expect(payload).toMatchObject({ socketId: bob.id, displayName: 'Bob' });

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── PARTICIPANTS_UPDATED ──────────────────────────────────────────────────
  describe('PARTICIPANTS_UPDATED', () => {
    it('broadcasts participant list to all room members when a client joins', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      // Alice joins first; expect a PARTICIPANTS_UPDATED with just Alice.
      const aliceUpdate1 = once(alice, 'PARTICIPANTS_UPDATED');
      await joinRoom(alice, 'room-pu', 'Alice');
      const { participants: afterAlice } = await aliceUpdate1;
      expect(afterAlice).toHaveLength(1);
      expect(afterAlice[0]).toMatchObject({ socketId: alice.id, displayName: 'Alice' });

      // Bob joins; both Alice and Bob should receive an updated list.
      const aliceUpdate2 = once(alice, 'PARTICIPANTS_UPDATED');
      const bobUpdate1 = once(bob, 'PARTICIPANTS_UPDATED');
      await joinRoom(bob, 'room-pu', 'Bob');

      const [{ participants: afterBobAlice }, { participants: afterBobBob }] = await Promise.all([
        aliceUpdate2,
        bobUpdate1,
      ]);
      expect(afterBobAlice).toHaveLength(2);
      expect(afterBobBob).toHaveLength(2);

      alice.disconnect();
      bob.disconnect();
    });

    it('broadcasts updated participant list when a client leaves', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-pu-leave', 'Alice');
      await joinRoom(bob, 'room-pu-leave', 'Bob');

      const aliceUpdate = once(alice, 'PARTICIPANTS_UPDATED');
      await leaveRoom(bob, 'room-pu-leave');

      const { participants } = await aliceUpdate;
      expect(participants).toHaveLength(1);
      expect(participants[0]).toMatchObject({ socketId: alice.id });

      alice.disconnect();
    });

    it('sanitises displayName — truncates to 50 chars', async () => {
      const alice = await connect();
      const longName = 'A'.repeat(100);

      const update = once(alice, 'PARTICIPANTS_UPDATED');
      await joinRoom(alice, 'room-pu-sanitise', longName);
      const { participants } = await update;

      expect(participants[0].displayName).toHaveLength(50);
      alice.disconnect();
    });
  });

  // ── leave_room ────────────────────────────────────────────────────────────
  describe('leave_room', () => {
    it('notifies remaining peers when a client leaves', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      // Both join and wait for acks before proceeding.
      await joinRoom(alice, 'room-2');
      await joinRoom(bob, 'room-2');

      const peerLeft = once(alice, 'peer_left');
      await leaveRoom(bob, 'room-2');

      const payload = await peerLeft;
      expect(payload).toMatchObject({ socketId: bob.id });

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── SYNC_HEARTBEAT ────────────────────────────────────────────────────────
  describe('SYNC_HEARTBEAT', () => {
    it('relays heartbeat to other room members', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-hb');
      await joinRoom(bob, 'room-hb');

      const heartbeatReceived = once(bob, 'SYNC_HEARTBEAT');
      alice.emit('SYNC_HEARTBEAT', {
        roomId: 'room-hb',
        hostPositionMs: 12345,
        hostTimestamp: 99999,
      });

      const payload = await heartbeatReceived;
      expect(payload).toMatchObject({ hostPositionMs: 12345, hostTimestamp: 99999 });
      // roomId must NOT be forwarded to peers
      expect(payload.roomId).toBeUndefined();

      alice.disconnect();
      bob.disconnect();
    });

    it('does not echo heartbeat back to the sender', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-echo');

      let echoed = false;
      alice.on('SYNC_HEARTBEAT', () => { echoed = true; });
      alice.emit('SYNC_HEARTBEAT', { roomId: 'room-echo', hostPositionMs: 0, hostTimestamp: 0 });

      await new Promise((r) => setTimeout(r, 100));
      expect(echoed).toBe(false);

      alice.disconnect();
    });
  });

  // ── QUEUE_UPDATED ─────────────────────────────────────────────────────────
  describe('QUEUE_UPDATED', () => {
    it('relays normalized queue (only id and title) to other room members', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-q');
      await joinRoom(bob, 'room-q');

      // The payload includes extra client-only fields; the server normalizes to { id, title }.
      const queue = [{ id: '1', title: 'Song', artist: 'Artist', youtubeVideoId: 'abc', durationMs: 180000 }];
      const queueReceived = once(bob, 'QUEUE_UPDATED');
      alice.emit('QUEUE_UPDATED', { roomId: 'room-q', queue });

      const received = await queueReceived;
      // Only id and title are forwarded — extra fields are stripped by normalization.
      expect(received).toEqual([{ id: '1', title: 'Song' }]);

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── PLAY ──────────────────────────────────────────────────────────────────
  describe('PLAY', () => {
    it('relays PLAY to other room members without roomId', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-play');
      await joinRoom(bob, 'room-play');

      const playReceived = once(bob, 'PLAY');
      alice.emit('PLAY', { roomId: 'room-play', positionMs: 5000 });

      const payload = await playReceived;
      expect(payload).toMatchObject({ positionMs: 5000 });
      expect(payload.roomId).toBeUndefined();

      alice.disconnect();
      bob.disconnect();
    });

    it('does not echo PLAY back to the sender', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-play-echo');

      let echoed = false;
      alice.on('PLAY', () => { echoed = true; });
      alice.emit('PLAY', { roomId: 'room-play-echo', positionMs: 0 });

      await new Promise((r) => setTimeout(r, 100));
      expect(echoed).toBe(false);

      alice.disconnect();
    });

    it('updates room state so late joiners receive isPlaying=true', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-play-state');
      alice.emit('PLAY', { roomId: 'room-play-state', positionMs: 3000 });

      // Give the server time to process the event before bob joins
      await new Promise((r) => setTimeout(r, 50));

      const bob = await connect();
      const ack = await joinRoom(bob, 'room-play-state');

      expect(ack.state).toMatchObject({ isPlaying: true, positionMs: 3000 });

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── PAUSE ─────────────────────────────────────────────────────────────────
  describe('PAUSE', () => {
    it('relays PAUSE to other room members without roomId', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-pause');
      await joinRoom(bob, 'room-pause');

      const pauseReceived = once(bob, 'PAUSE');
      alice.emit('PAUSE', { roomId: 'room-pause', positionMs: 7500 });

      const payload = await pauseReceived;
      expect(payload).toMatchObject({ positionMs: 7500 });
      expect(payload.roomId).toBeUndefined();

      alice.disconnect();
      bob.disconnect();
    });

    it('does not echo PAUSE back to the sender', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-pause-echo');

      let echoed = false;
      alice.on('PAUSE', () => { echoed = true; });
      alice.emit('PAUSE', { roomId: 'room-pause-echo', positionMs: 0 });

      await new Promise((r) => setTimeout(r, 100));
      expect(echoed).toBe(false);

      alice.disconnect();
    });

    it('updates room state so late joiners receive isPlaying=false', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-pause-state');
      // First play, then pause
      alice.emit('PLAY', { roomId: 'room-pause-state', positionMs: 1000 });
      await new Promise((r) => setTimeout(r, 30));
      alice.emit('PAUSE', { roomId: 'room-pause-state', positionMs: 2000 });
      await new Promise((r) => setTimeout(r, 50));

      const bob = await connect();
      const ack = await joinRoom(bob, 'room-pause-state');

      expect(ack.state).toMatchObject({ isPlaying: false, positionMs: 2000 });

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── SEEK ──────────────────────────────────────────────────────────────────
  describe('SEEK', () => {
    it('relays SEEK to other room members without roomId', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-seek');
      await joinRoom(bob, 'room-seek');

      const seekReceived = once(bob, 'SEEK');
      alice.emit('SEEK', { roomId: 'room-seek', positionMs: 12000 });

      const payload = await seekReceived;
      expect(payload).toMatchObject({ positionMs: 12000 });
      expect(payload.roomId).toBeUndefined();

      alice.disconnect();
      bob.disconnect();
    });

    it('does not echo SEEK back to the sender', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-seek-echo');

      let echoed = false;
      alice.on('SEEK', () => { echoed = true; });
      alice.emit('SEEK', { roomId: 'room-seek-echo', positionMs: 0 });

      await new Promise((r) => setTimeout(r, 100));
      expect(echoed).toBe(false);

      alice.disconnect();
    });

    it('updates room state positionMs while preserving isPlaying', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-seek-state');
      alice.emit('PLAY', { roomId: 'room-seek-state', positionMs: 1000 });
      await new Promise((r) => setTimeout(r, 30));
      alice.emit('SEEK', { roomId: 'room-seek-state', positionMs: 45000 });
      await new Promise((r) => setTimeout(r, 50));

      const bob = await connect();
      const ack = await joinRoom(bob, 'room-seek-state');

      expect(ack.state).toMatchObject({ isPlaying: true, positionMs: 45000 });

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── join_room state ───────────────────────────────────────────────────────
  describe('join_room state', () => {
    it('returns null state for a brand-new room', async () => {
      const alice = await connect();
      const ack = await joinRoom(alice, 'room-new-state');

      expect(ack.state).toBeNull();

      alice.disconnect();
    });
  });

  // ── GET /room/:roomId/status ──────────────────────────────────────────────
  describe('GET /room/:roomId/status', () => {
    it('returns active=false and listenerCount=0 for an empty room', async () => {
      const res = await request(app).get('/room/status-empty/status');
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ active: false, listenerCount: 0 });
    });

    it('returns active=true with correct listenerCount while members are connected', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-status-active');
      await joinRoom(bob, 'room-status-active');

      const res = await request(app).get('/room/room-status-active/status');
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ active: true, listenerCount: 2 });

      alice.disconnect();
      bob.disconnect();
    });

    it('returns active=false after all members leave', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-status-leave');
      await leaveRoom(alice, 'room-status-leave');
      // Allow time for the leave to propagate
      await new Promise((r) => setTimeout(r, 50));

      const res = await request(app).get('/room/room-status-leave/status');
      expect(res.status).toBe(200);
      expect(res.body).toMatchObject({ active: false, listenerCount: 0 });

      alice.disconnect();
    });

    it('returns 400 for an invalid roomId', async () => {
      const res = await request(app).get('/room/' + encodeURIComponent('<bad>') + '/status');
      expect(res.status).toBe(400);
    });
  });

  // ── positionMs validation ─────────────────────────────────────────────────
  describe('positionMs validation', () => {
    it.each([
      ['missing (undefined)', undefined],
      ['NaN', NaN],
      ['Infinity', Infinity],
      ['negative', -1],
      ['string', '5000'],
    ])('ignores PLAY with %s positionMs', async (_label, badPos) => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-valid-play');
      await joinRoom(bob, 'room-valid-play');

      let received = false;
      bob.on('PLAY', () => { received = true; });
      alice.emit('PLAY', { roomId: 'room-valid-play', positionMs: badPos });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });

    it.each([
      ['missing (undefined)', undefined],
      ['negative', -100],
    ])('ignores PAUSE with %s positionMs', async (_label, badPos) => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-valid-pause');
      await joinRoom(bob, 'room-valid-pause');

      let received = false;
      bob.on('PAUSE', () => { received = true; });
      alice.emit('PAUSE', { roomId: 'room-valid-pause', positionMs: badPos });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });

    it.each([
      ['missing (undefined)', undefined],
      ['negative', -500],
    ])('ignores SEEK with %s positionMs', async (_label, badPos) => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-valid-seek');
      await joinRoom(bob, 'room-valid-seek');

      let received = false;
      bob.on('SEEK', () => { received = true; });
      alice.emit('SEEK', { roomId: 'room-valid-seek', positionMs: badPos });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── membership check ──────────────────────────────────────────────────────
  describe('membership check', () => {
    it('does not broadcast PLAY from a non-member socket', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-member-play');
      // intruder intentionally does NOT join the room

      let received = false;
      alice.on('PLAY', () => { received = true; });
      intruder.emit('PLAY', { roomId: 'room-member-play', positionMs: 1000 });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      intruder.disconnect();
    });

    it('does not broadcast PAUSE from a non-member socket', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-member-pause');

      let received = false;
      alice.on('PAUSE', () => { received = true; });
      intruder.emit('PAUSE', { roomId: 'room-member-pause', positionMs: 2000 });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      intruder.disconnect();
    });

    it('does not broadcast SEEK from a non-member socket', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-member-seek');

      let received = false;
      alice.on('SEEK', () => { received = true; });
      intruder.emit('SEEK', { roomId: 'room-member-seek', positionMs: 3000 });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      intruder.disconnect();
    });
  });

  // ── end_session ───────────────────────────────────────────────────────────
  describe('end_session', () => {
    /** Ends a session and waits for the server acknowledgement before resolving. */
    function endSession(socket, roomId) {
      return new Promise((resolve, reject) => {
        socket.emit('end_session', roomId, (ack) => {
          if (ack && ack.error) reject(new Error(ack.error));
          else resolve(ack);
        });
      });
    }

    it('broadcasts ROOM_CLOSED to all members including the host', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-end-1');
      await joinRoom(bob, 'room-end-1');

      const aliceClosed = once(alice, 'ROOM_CLOSED');
      const bobClosed = once(bob, 'ROOM_CLOSED');

      await endSession(alice, 'room-end-1');

      await Promise.all([aliceClosed, bobClosed]);

      alice.disconnect();
      bob.disconnect();
    });

    it('clears room state so late joiners see null', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-end-2');
      alice.emit('PLAY', { roomId: 'room-end-2', positionMs: 9000 });
      await new Promise((r) => setTimeout(r, 50));

      await endSession(alice, 'room-end-2');
      await new Promise((r) => setTimeout(r, 50));

      const bob = await connect();
      const ack = await joinRoom(bob, 'room-end-2');
      expect(ack.state).toBeNull();

      alice.disconnect();
      bob.disconnect();
    });

    it('rejects end_session with an invalid roomId', async () => {
      const alice = await connect();

      const ack = await new Promise((resolve) => {
        alice.emit('end_session', '', resolve);
      });
      expect(ack).toMatchObject({ error: expect.any(String) });

      alice.disconnect();
    });

    it('rejects end_session from a socket not in the room', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-end-nonmember');

      const ack = await new Promise((resolve) => {
        intruder.emit('end_session', 'room-end-nonmember', resolve);
      });
      expect(ack).toMatchObject({ error: expect.any(String) });

      alice.disconnect();
      intruder.disconnect();
    });
  });

  // ── room state cleanup ────────────────────────────────────────────────────
  describe('room state cleanup', () => {
    it('clears state when last member leaves via leave_room', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-cleanup-leave');
      alice.emit('PLAY', { roomId: 'room-cleanup-leave', positionMs: 1000 });
      await new Promise((r) => setTimeout(r, 50));

      await leaveRoom(alice, 'room-cleanup-leave');
      await new Promise((r) => setTimeout(r, 50));

      // A fresh joiner should see null state
      const bob = await connect();
      const ack = await joinRoom(bob, 'room-cleanup-leave');
      expect(ack.state).toBeNull();

      alice.disconnect();
      bob.disconnect();
    });

    it('clears state when last member disconnects', async () => {
      const alice = await connect();
      await joinRoom(alice, 'room-cleanup-disconnect');
      alice.emit('PLAY', { roomId: 'room-cleanup-disconnect', positionMs: 2000 });
      await new Promise((r) => setTimeout(r, 50));

      alice.disconnect();
      await new Promise((r) => setTimeout(r, 100));

      const bob = await connect();
      const ack = await joinRoom(bob, 'room-cleanup-disconnect');
      expect(ack.state).toBeNull();

      bob.disconnect();
    });

    it('preserves state while at least one member remains', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-cleanup-partial');
      await joinRoom(bob, 'room-cleanup-partial');
      alice.emit('PLAY', { roomId: 'room-cleanup-partial', positionMs: 5000 });
      await new Promise((r) => setTimeout(r, 50));

      // Alice leaves but Bob remains
      await leaveRoom(alice, 'room-cleanup-partial');
      await new Promise((r) => setTimeout(r, 50));

      const carol = await connect();
      const ack = await joinRoom(carol, 'room-cleanup-partial');
      expect(ack.state).toMatchObject({ isPlaying: true, positionMs: 5000 });

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });
  });

  // ── host permissions ─────────────────────────────────────────────────────
  describe('host permissions', () => {
    it('does not relay PLAY from a guest when a host is already established', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);

      await joinRoom(alice, 'room-host-play');
      await joinRoom(bob, 'room-host-play');
      await joinRoom(carol, 'room-host-play');

      // Alice sends PLAY first — she becomes the host
      alice.emit('PLAY', { roomId: 'room-host-play', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      // Bob (a non-host) tries to send PLAY — must be silently dropped
      let received = false;
      carol.on('PLAY', () => { received = true; });
      bob.emit('PLAY', { roomId: 'room-host-play', positionMs: 2000 });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('does not relay PAUSE from a guest when a host is established', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);

      await joinRoom(alice, 'room-host-pause');
      await joinRoom(bob, 'room-host-pause');
      await joinRoom(carol, 'room-host-pause');

      alice.emit('PLAY', { roomId: 'room-host-pause', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let received = false;
      carol.on('PAUSE', () => { received = true; });
      bob.emit('PAUSE', { roomId: 'room-host-pause', positionMs: 1000 });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('does not relay SEEK from a guest when a host is established', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);

      await joinRoom(alice, 'room-host-seek');
      await joinRoom(bob, 'room-host-seek');
      await joinRoom(carol, 'room-host-seek');

      alice.emit('PLAY', { roomId: 'room-host-seek', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let received = false;
      carol.on('SEEK', () => { received = true; });
      bob.emit('SEEK', { roomId: 'room-host-seek', positionMs: 5000 });

      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('guest PLAY does not update room state', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-host-play-state');
      await joinRoom(bob, 'room-host-play-state');

      // Alice (host) plays at 1000ms
      alice.emit('PLAY', { roomId: 'room-host-play-state', positionMs: 1000 });
      await new Promise((r) => setTimeout(r, 50));

      // Bob (guest) attempts to play at a different position — must be ignored
      bob.emit('PLAY', { roomId: 'room-host-play-state', positionMs: 9999 });
      await new Promise((r) => setTimeout(r, 50));

      const carol = await connect();
      const ack = await joinRoom(carol, 'room-host-play-state');
      // State must reflect Alice's original play, not Bob's attempt
      expect(ack.state).toMatchObject({ positionMs: 1000, hostId: alice.id });

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('allows host to PLAY, PAUSE, and SEEK in sequence', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-host-sequence');
      await joinRoom(bob, 'room-host-sequence');

      const playP = once(bob, 'PLAY');
      alice.emit('PLAY', { roomId: 'room-host-sequence', positionMs: 0 });
      await playP;

      const pauseP = once(bob, 'PAUSE');
      alice.emit('PAUSE', { roomId: 'room-host-sequence', positionMs: 5000 });
      await pauseP;

      const seekP = once(bob, 'SEEK');
      alice.emit('SEEK', { roomId: 'room-host-sequence', positionMs: 30000 });
      await seekP;

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── TRANSFER_HOST ─────────────────────────────────────────────────────────
  describe('TRANSFER_HOST', () => {
    it('updates hostId and broadcasts HOST_TRANSFERRED to all members', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);

      await joinRoom(alice, 'room-transfer-1');
      await joinRoom(bob, 'room-transfer-1');
      await joinRoom(carol, 'room-transfer-1');

      // Alice becomes host
      alice.emit('PLAY', { roomId: 'room-transfer-1', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      const aliceTransferred = once(alice, 'HOST_TRANSFERRED');
      const bobTransferred = once(bob, 'HOST_TRANSFERRED');
      const carolTransferred = once(carol, 'HOST_TRANSFERRED');

      alice.emit('TRANSFER_HOST', { roomId: 'room-transfer-1', newHostSocketId: bob.id });

      const [alicePayload, bobPayload, carolPayload] = await Promise.all([
        aliceTransferred,
        bobTransferred,
        carolTransferred,
      ]);

      expect(alicePayload).toMatchObject({ newHostSocketId: bob.id });
      expect(bobPayload).toMatchObject({ newHostSocketId: bob.id });
      expect(carolPayload).toMatchObject({ newHostSocketId: bob.id });

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('updates room state hostId after transfer', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-transfer-state');
      await joinRoom(bob, 'room-transfer-state');

      alice.emit('PLAY', { roomId: 'room-transfer-state', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      await new Promise((resolve) => {
        alice.emit('TRANSFER_HOST', { roomId: 'room-transfer-state', newHostSocketId: bob.id });
        bob.once('HOST_TRANSFERRED', resolve);
      });

      const carol = await connect();
      const ack = await joinRoom(carol, 'room-transfer-state');
      expect(ack.state.hostId).toBe(bob.id);

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('new host can PLAY after transfer; old host cannot', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);

      await joinRoom(alice, 'room-transfer-play');
      await joinRoom(bob, 'room-transfer-play');
      await joinRoom(carol, 'room-transfer-play');

      alice.emit('PLAY', { roomId: 'room-transfer-play', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      // Transfer host from alice to bob
      await new Promise((resolve) => {
        alice.emit('TRANSFER_HOST', { roomId: 'room-transfer-play', newHostSocketId: bob.id });
        bob.once('HOST_TRANSFERRED', resolve);
      });

      // Alice (former host) tries to PLAY — should be rejected
      let alicePlayReceived = false;
      carol.on('PLAY', () => { alicePlayReceived = true; });
      alice.emit('PLAY', { roomId: 'room-transfer-play', positionMs: 9000 });
      await new Promise((r) => setTimeout(r, 100));
      expect(alicePlayReceived).toBe(false);

      // Bob (new host) can PLAY
      const bobPlayReceived = once(carol, 'PLAY');
      bob.emit('PLAY', { roomId: 'room-transfer-play', positionMs: 500 });
      const payload = await bobPlayReceived;
      expect(payload).toMatchObject({ positionMs: 500 });

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('ignores TRANSFER_HOST from a non-host socket', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);

      await joinRoom(alice, 'room-transfer-nonhost');
      await joinRoom(bob, 'room-transfer-nonhost');
      await joinRoom(carol, 'room-transfer-nonhost');

      alice.emit('PLAY', { roomId: 'room-transfer-nonhost', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let transferReceived = false;
      alice.on('HOST_TRANSFERRED', () => { transferReceived = true; });

      // Bob (non-host) tries to transfer to carol — must be ignored
      bob.emit('TRANSFER_HOST', { roomId: 'room-transfer-nonhost', newHostSocketId: carol.id });
      await new Promise((r) => setTimeout(r, 100));
      expect(transferReceived).toBe(false);

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('ignores TRANSFER_HOST when newHostSocketId is not in the room', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-transfer-absent');
      await joinRoom(bob, 'room-transfer-absent');

      alice.emit('PLAY', { roomId: 'room-transfer-absent', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let transferReceived = false;
      bob.on('HOST_TRANSFERRED', () => { transferReceived = true; });

      // 'ghost-socket-id' is not in the room
      alice.emit('TRANSFER_HOST', { roomId: 'room-transfer-absent', newHostSocketId: 'ghost-socket-id' });
      await new Promise((r) => setTimeout(r, 100));
      expect(transferReceived).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });

    it('ignores TRANSFER_HOST from a non-member socket', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-transfer-intruder');
      alice.emit('PLAY', { roomId: 'room-transfer-intruder', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let transferReceived = false;
      alice.on('HOST_TRANSFERRED', () => { transferReceived = true; });

      intruder.emit('TRANSFER_HOST', { roomId: 'room-transfer-intruder', newHostSocketId: alice.id });
      await new Promise((r) => setTimeout(r, 100));
      expect(transferReceived).toBe(false);

      alice.disconnect();
      intruder.disconnect();
    });

    it('join_room ack includes socketId', async () => {
      const alice = await connect();
      const ack = await joinRoom(alice, 'room-socketid');
      expect(ack.socketId).toBe(alice.id);
      alice.disconnect();
    });

    it('uses "Someone" as senderName when none is provided', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-chat-noname');
      await joinRoom(bob, 'room-chat-noname');

      const bobReceived = once(bob, 'CHAT_MESSAGE');
      alice.emit('CHAT_MESSAGE', {
        roomId: 'room-chat-noname',
        text: 'Hello',
      });

      const payload = await bobReceived;
      expect(payload.senderName).toBe('Someone');

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── REACTION ──────────────────────────────────────────────────────────────
  describe('REACTION', () => {
    it('relays an emoji reaction to other room members', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-reaction-1');
      await joinRoom(bob, 'room-reaction-1');

      const bobReceived = once(bob, 'REACTION');
      alice.emit('REACTION', { roomId: 'room-reaction-1', emoji: '🔥' });

      const payload = await bobReceived;
      expect(payload).toMatchObject({ emoji: '🔥' });

      alice.disconnect();
      bob.disconnect();
    });

    it('does not echo the reaction back to the sender', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-reaction-noecho');
      await joinRoom(bob, 'room-reaction-noecho');

      let aliceReceived = false;
      alice.on('REACTION', () => { aliceReceived = true; });
      alice.emit('REACTION', { roomId: 'room-reaction-noecho', emoji: '❤️' });

      await new Promise((r) => setTimeout(r, 100));
      expect(aliceReceived).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });

    it('ignores a reaction from a socket not in the room', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-reaction-nonmember');

      let aliceReceived = false;
      alice.on('REACTION', () => { aliceReceived = true; });
      intruder.emit('REACTION', { roomId: 'room-reaction-nonmember', emoji: '😂' });

      await new Promise((r) => setTimeout(r, 100));
      expect(aliceReceived).toBe(false);

      alice.disconnect();
      intruder.disconnect();
    });
  });

  // ── TYPING ────────────────────────────────────────────────────────────────
  describe('TYPING', () => {
    it('relays a typing indicator to other room members with socket.id as senderId', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-typing-1');
      await joinRoom(bob, 'room-typing-1');

      const bobReceived = once(bob, 'TYPING');
      alice.emit('TYPING', {
        roomId: 'room-typing-1',
        senderName: 'Alice',
      });

      const payload = await bobReceived;
      // The server derives senderId from socket.id, not the client payload.
      expect(payload).toMatchObject({ senderId: alice.id, senderName: 'Alice' });

      alice.disconnect();
      bob.disconnect();
    });

    it('does not echo typing back to the sender', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-typing-noecho');
      await joinRoom(bob, 'room-typing-noecho');

      let aliceReceived = false;
      alice.on('TYPING', () => { aliceReceived = true; });
      alice.emit('TYPING', {
        roomId: 'room-typing-noecho',
        senderName: 'Alice',
      });

      await new Promise((r) => setTimeout(r, 100));
      expect(aliceReceived).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });

    it('ignores typing from a socket not in the room', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-typing-nonmember');

      let aliceReceived = false;
      alice.on('TYPING', () => { aliceReceived = true; });
      intruder.emit('TYPING', {
        roomId: 'room-typing-nonmember',
        senderName: 'Intruder',
      });

      await new Promise((r) => setTimeout(r, 100));
      expect(aliceReceived).toBe(false);

      alice.disconnect();
      intruder.disconnect();
    });

    it('uses "Someone" as senderName when none is provided', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-typing-noname');
      await joinRoom(bob, 'room-typing-noname');

      const bobReceived = once(bob, 'TYPING');
      alice.emit('TYPING', { roomId: 'room-typing-noname' });

      const payload = await bobReceived;
      expect(payload.senderName).toBe('Someone');

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── SET_DEMOCRATIC_MODE / DEMOCRATIC_MODE_CHANGED ─────────────────────────
  describe('SET_DEMOCRATIC_MODE / DEMOCRATIC_MODE_CHANGED', () => {
    it('host can enable democratic mode; broadcasts to all members', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-democratic-1');
      await joinRoom(bob, 'room-democratic-1');

      alice.emit('PLAY', { roomId: 'room-democratic-1', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      const bobReceived = once(bob, 'DEMOCRATIC_MODE_CHANGED');
      const aliceReceived = once(alice, 'DEMOCRATIC_MODE_CHANGED');
      alice.emit('SET_DEMOCRATIC_MODE', { roomId: 'room-democratic-1', enabled: true });

      const [alicePayload, bobPayload] = await Promise.all([aliceReceived, bobReceived]);
      expect(alicePayload).toMatchObject({ enabled: true });
      expect(bobPayload).toMatchObject({ enabled: true });

      alice.disconnect();
      bob.disconnect();
    });

    it('host can disable democratic mode', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-democratic-2');
      await joinRoom(bob, 'room-democratic-2');

      alice.emit('PLAY', { roomId: 'room-democratic-2', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      alice.emit('SET_DEMOCRATIC_MODE', { roomId: 'room-democratic-2', enabled: true });
      await new Promise((r) => setTimeout(r, 50));

      const bobReceived = once(bob, 'DEMOCRATIC_MODE_CHANGED');
      alice.emit('SET_DEMOCRATIC_MODE', { roomId: 'room-democratic-2', enabled: false });

      const payload = await bobReceived;
      expect(payload).toMatchObject({ enabled: false });

      alice.disconnect();
      bob.disconnect();
    });

    it('non-host cannot set democratic mode', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-democratic-3');
      await joinRoom(bob, 'room-democratic-3');

      alice.emit('PLAY', { roomId: 'room-democratic-3', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let received = false;
      alice.on('DEMOCRATIC_MODE_CHANGED', () => { received = true; });
      bob.emit('SET_DEMOCRATIC_MODE', { roomId: 'room-democratic-3', enabled: true });
      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── PLAY/PAUSE/SEEK in democratic mode ────────────────────────────────────
  describe('PLAY/PAUSE/SEEK in democratic mode', () => {
    it('guest can send PLAY when democraticMode=true', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);
      await joinRoom(alice, 'room-democratic-play');
      await joinRoom(bob, 'room-democratic-play');
      await joinRoom(carol, 'room-democratic-play');

      alice.emit('PLAY', { roomId: 'room-democratic-play', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      // Enable democratic mode
      await new Promise((resolve) => {
        alice.emit('SET_DEMOCRATIC_MODE', { roomId: 'room-democratic-play', enabled: true });
        bob.once('DEMOCRATIC_MODE_CHANGED', resolve);
      });

      // Bob (guest) can now send PLAY
      const carolReceived = once(carol, 'PLAY');
      bob.emit('PLAY', { roomId: 'room-democratic-play', positionMs: 5000 });
      const payload = await carolReceived;
      expect(payload).toMatchObject({ positionMs: 5000 });

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });

    it('non-members still blocked from PLAY even in democratic mode', async () => {
      const [alice, intruder] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-democratic-nonmember');

      alice.emit('PLAY', { roomId: 'room-democratic-nonmember', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      alice.emit('SET_DEMOCRATIC_MODE', { roomId: 'room-democratic-nonmember', enabled: true });
      await new Promise((r) => setTimeout(r, 50));

      let received = false;
      alice.on('PLAY', () => { received = true; });
      intruder.emit('PLAY', { roomId: 'room-democratic-nonmember', positionMs: 1000 });
      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      intruder.disconnect();
    });
  });

  // ── SET_AUTO_APPROVE_QUEUE / AUTO_APPROVE_QUEUE_CHANGED ───────────────────
  describe('SET_AUTO_APPROVE_QUEUE / AUTO_APPROVE_QUEUE_CHANGED', () => {
    it('host can disable auto-approve; broadcasts to all members', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-autoapprove-1');
      await joinRoom(bob, 'room-autoapprove-1');

      alice.emit('PLAY', { roomId: 'room-autoapprove-1', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      const bobReceived = once(bob, 'AUTO_APPROVE_QUEUE_CHANGED');
      const aliceReceived = once(alice, 'AUTO_APPROVE_QUEUE_CHANGED');
      alice.emit('SET_AUTO_APPROVE_QUEUE', { roomId: 'room-autoapprove-1', enabled: false });

      const [alicePayload, bobPayload] = await Promise.all([aliceReceived, bobReceived]);
      expect(alicePayload).toMatchObject({ enabled: false });
      expect(bobPayload).toMatchObject({ enabled: false });

      alice.disconnect();
      bob.disconnect();
    });

    it('non-host cannot set auto-approve', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-autoapprove-2');
      await joinRoom(bob, 'room-autoapprove-2');

      alice.emit('PLAY', { roomId: 'room-autoapprove-2', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let received = false;
      alice.on('AUTO_APPROVE_QUEUE_CHANGED', () => { received = true; });
      bob.emit('SET_AUTO_APPROVE_QUEUE', { roomId: 'room-autoapprove-2', enabled: false });
      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });
  });

  // ── REQUEST_QUEUE_ADD ─────────────────────────────────────────────────────
  describe('REQUEST_QUEUE_ADD', () => {
    it('auto-approves and broadcasts QUEUE_UPDATED when autoApproveQueue=true', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-queueadd-auto');
      await joinRoom(bob, 'room-queueadd-auto');

      alice.emit('PLAY', { roomId: 'room-queueadd-auto', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      const aliceReceived = once(alice, 'QUEUE_UPDATED');
      bob.emit('REQUEST_QUEUE_ADD', {
        roomId: 'room-queueadd-auto',
        track: { id: 'track-1', title: 'My Song' },
      });

      const queue = await aliceReceived;
      expect(Array.isArray(queue)).toBe(true);
      expect(queue.some((t) => t.id === 'track-1')).toBe(true);

      alice.disconnect();
      bob.disconnect();
    });

    it('sends QUEUE_ADD_REQUEST to host only when autoApproveQueue=false', async () => {
      const [alice, bob, carol] = await Promise.all([connect(), connect(), connect()]);
      await joinRoom(alice, 'room-queueadd-manual');
      await joinRoom(bob, 'room-queueadd-manual');
      await joinRoom(carol, 'room-queueadd-manual');

      alice.emit('PLAY', { roomId: 'room-queueadd-manual', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      // Disable auto-approve
      await new Promise((resolve) => {
        alice.emit('SET_AUTO_APPROVE_QUEUE', { roomId: 'room-queueadd-manual', enabled: false });
        bob.once('AUTO_APPROVE_QUEUE_CHANGED', resolve);
      });

      const aliceRequest = once(alice, 'QUEUE_ADD_REQUEST');
      let carolReceived = false;
      carol.on('QUEUE_ADD_REQUEST', () => { carolReceived = true; });

      bob.emit('REQUEST_QUEUE_ADD', {
        roomId: 'room-queueadd-manual',
        track: { id: 'track-2', title: 'Another Song' },
      });

      const payload = await aliceRequest;
      expect(payload).toMatchObject({ id: 'track-2', title: 'Another Song' });
      await new Promise((r) => setTimeout(r, 100));
      expect(carolReceived).toBe(false);

      alice.disconnect();
      bob.disconnect();
      carol.disconnect();
    });
  });

  // ── APPROVE_QUEUE_ADD ─────────────────────────────────────────────────────
  describe('APPROVE_QUEUE_ADD', () => {
    it('host approves and QUEUE_UPDATED is broadcast to all members', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-approve-1');
      await joinRoom(bob, 'room-approve-1');

      alice.emit('PLAY', { roomId: 'room-approve-1', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      const bobReceived = once(bob, 'QUEUE_UPDATED');
      const aliceReceived = once(alice, 'QUEUE_UPDATED');
      alice.emit('APPROVE_QUEUE_ADD', {
        roomId: 'room-approve-1',
        track: { id: 'approved-track', title: 'Approved Song' },
      });

      const [aliceQueue, bobQueue] = await Promise.all([aliceReceived, bobReceived]);
      expect(aliceQueue.some((t) => t.id === 'approved-track')).toBe(true);
      expect(bobQueue.some((t) => t.id === 'approved-track')).toBe(true);

      alice.disconnect();
      bob.disconnect();
    });

    it('non-host APPROVE_QUEUE_ADD is ignored', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);
      await joinRoom(alice, 'room-approve-2');
      await joinRoom(bob, 'room-approve-2');

      alice.emit('PLAY', { roomId: 'room-approve-2', positionMs: 0 });
      await new Promise((r) => setTimeout(r, 50));

      let received = false;
      alice.on('QUEUE_UPDATED', () => { received = true; });
      bob.emit('APPROVE_QUEUE_ADD', {
        roomId: 'room-approve-2',
        track: { id: 'sneaky-track', title: 'Sneaky Song' },
      });
      await new Promise((r) => setTimeout(r, 100));
      expect(received).toBe(false);

      alice.disconnect();
      bob.disconnect();
    });
  });
});

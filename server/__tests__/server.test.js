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
  function joinRoom(socket, roomId) {
    return new Promise((resolve, reject) => {
      socket.emit('join_room', roomId, (ack) => {
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
    it('relays queue to other room members', async () => {
      const [alice, bob] = await Promise.all([connect(), connect()]);

      await joinRoom(alice, 'room-q');
      await joinRoom(bob, 'room-q');

      const queue = [{ id: '1', title: 'Song', artist: 'Artist', youtubeVideoId: 'abc', durationMs: 180000 }];
      const queueReceived = once(bob, 'QUEUE_UPDATED');
      alice.emit('QUEUE_UPDATED', { roomId: 'room-q', queue });

      const received = await queueReceived;
      expect(received).toEqual(queue);

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
});

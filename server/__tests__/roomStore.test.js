'use strict';

const { validateRoomData, isVideoRef, ROOM_TTL_SECONDS } = require('../src/roomStore');
const { createApp } = require('../src/server');
const { io: ioc } = require('socket.io-client');

// ── validateRoomData ──────────────────────────────────────────────────────────

describe('validateRoomData', () => {
  const valid = {
    roomId: 'room-1',
    hostId: 'socket-abc',
    playbackState: 'PLAYING',
    isPlaying: true,
    positionMs: 0,
    currentVideo: null,
    queue: [],
    updatedAt: Date.now(),
  };

  it('accepts a fully valid RoomData object', () => {
    expect(() => validateRoomData(valid)).not.toThrow();
    expect(validateRoomData(valid)).toBe(valid);
  });

  it('accepts a PAUSED playbackState', () => {
    expect(() => validateRoomData({ ...valid, playbackState: 'PAUSED', isPlaying: false })).not.toThrow();
  });

  it('accepts null hostId', () => {
    expect(() => validateRoomData({ ...valid, hostId: null })).not.toThrow();
  });

  it('accepts a currentVideo object', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: 'v1', title: 'Song A' } }),
    ).not.toThrow();
  });

  it('accepts a non-empty queue', () => {
    expect(() =>
      validateRoomData({ ...valid, queue: [{ id: 'v1', title: 'Song A' }] }),
    ).not.toThrow();
  });

  it('throws when data is null', () => {
    expect(() => validateRoomData(null)).toThrow(TypeError);
  });

  it('throws when data is not an object', () => {
    expect(() => validateRoomData('string')).toThrow(TypeError);
  });

  it('throws when roomId is empty', () => {
    expect(() => validateRoomData({ ...valid, roomId: '' })).toThrow(TypeError);
  });

  it('throws when roomId is not a string', () => {
    expect(() => validateRoomData({ ...valid, roomId: 42 })).toThrow(TypeError);
  });

  it('throws when hostId is a number', () => {
    expect(() => validateRoomData({ ...valid, hostId: 123 })).toThrow(TypeError);
  });

  it('throws when playbackState is invalid', () => {
    expect(() => validateRoomData({ ...valid, playbackState: 'STOPPED' })).toThrow(TypeError);
  });

  it('throws when isPlaying is not a boolean', () => {
    expect(() => validateRoomData({ ...valid, isPlaying: 1 })).toThrow(TypeError);
  });

  it('throws when positionMs is negative', () => {
    expect(() => validateRoomData({ ...valid, positionMs: -1 })).toThrow(TypeError);
  });

  it('throws when positionMs is NaN', () => {
    expect(() => validateRoomData({ ...valid, positionMs: NaN })).toThrow(TypeError);
  });

  it('throws when positionMs is Infinity', () => {
    expect(() => validateRoomData({ ...valid, positionMs: Infinity })).toThrow(TypeError);
  });

  it('throws when currentVideo has empty id', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: '', title: 'Song' } }),
    ).toThrow(TypeError);
  });

  it('throws when currentVideo has empty title', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: 'v1', title: '' } }),
    ).toThrow(TypeError);
  });

  it('throws when currentVideo is missing title', () => {
    expect(() =>
      validateRoomData({ ...valid, currentVideo: { id: 'v1' } }),
    ).toThrow(TypeError);
  });

  it('throws when queue contains an item with empty id', () => {
    expect(() =>
      validateRoomData({ ...valid, queue: [{ id: '', title: 'Song' }] }),
    ).toThrow(TypeError);
  });

  it('throws when queue contains an item with empty title', () => {
    expect(() =>
      validateRoomData({ ...valid, queue: [{ id: 'v1', title: '' }] }),
    ).toThrow(TypeError);
  });

  it('throws when queue is not an array', () => {
    expect(() => validateRoomData({ ...valid, queue: null })).toThrow(TypeError);
  });
});

// ── ROOM_TTL_SECONDS ──────────────────────────────────────────────────────────

describe('ROOM_TTL_SECONDS', () => {
  it('is 14400 seconds (4 hours)', () => {
    expect(ROOM_TTL_SECONDS).toBe(14400);
  });
});

// ── isVideoRef ────────────────────────────────────────────────────────────────

describe('isVideoRef', () => {
  it('returns true for a valid VideoRef', () => {
    expect(isVideoRef({ id: 'v1', title: 'Song' })).toBe(true);
  });

  it('returns false for null', () => {
    expect(isVideoRef(null)).toBe(false);
  });

  it('returns false when id is empty', () => {
    expect(isVideoRef({ id: '', title: 'Song' })).toBe(false);
  });

  it('returns false when title is empty', () => {
    expect(isVideoRef({ id: 'v1', title: '' })).toBe(false);
  });

  it('returns false when id is missing', () => {
    expect(isVideoRef({ title: 'Song' })).toBe(false);
  });
});

// ── Redis schema fields in join_room state ────────────────────────────────────

describe('Redis schema fields', () => {
  let httpServer, io, serverUrl;

  function connect() {
    return new Promise((resolve) => {
      const socket = ioc(serverUrl, { forceNew: true });
      socket.on('connect', () => resolve(socket));
    });
  }

  function joinRoom(socket, roomId) {
    return new Promise((resolve, reject) => {
      socket.emit('join_room', roomId, (ack) => {
        if (ack && ack.error) reject(new Error(ack.error));
        else resolve(ack);
      });
    });
  }

  beforeAll((done) => {
    ({ httpServer, io } = createApp());
    httpServer.listen(0, () => {
      const { port } = httpServer.address();
      serverUrl = `http://localhost:${port}`;
      done();
    });
  });

  afterAll((done) => {
    io.close(done);
  });

  it('state includes hostId (first PLAY sender) and playbackState after PLAY', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-play');
    alice.emit('PLAY', { roomId: 'schema-play', positionMs: 1000 });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-play');

    expect(ack.state).toMatchObject({
      roomId: 'schema-play',
      hostId: alice.id,
      playbackState: 'PLAYING',
      isPlaying: true,
      positionMs: 1000,
      currentVideo: null,
      queue: [],
    });

    alice.disconnect();
    bob.disconnect();
  });

  it('playbackState is PAUSED after PAUSE event', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-pause');
    alice.emit('PAUSE', { roomId: 'schema-pause', positionMs: 5000 });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-pause');

    expect(ack.state).toMatchObject({
      playbackState: 'PAUSED',
      isPlaying: false,
      positionMs: 5000,
    });

    alice.disconnect();
    bob.disconnect();
  });

  it('hostId is preserved when a second socket sends PLAY', async () => {
    const [alice, bob] = await Promise.all([connect(), connect()]);
    await joinRoom(alice, 'schema-host');
    await joinRoom(bob, 'schema-host');

    // Alice triggers first PLAY — she becomes the host
    alice.emit('PLAY', { roomId: 'schema-host', positionMs: 0 });
    await new Promise((r) => setTimeout(r, 50));

    // Bob then sends PLAY — hostId must still be alice's ID
    bob.emit('PLAY', { roomId: 'schema-host', positionMs: 2000 });
    await new Promise((r) => setTimeout(r, 50));

    const carol = await connect();
    const ack = await joinRoom(carol, 'schema-host');

    expect(ack.state.hostId).toBe(alice.id);

    alice.disconnect();
    bob.disconnect();
    carol.disconnect();
  });

  it('queue and currentVideo are persisted via QUEUE_UPDATED', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-queue');

    // Establish room state first so QUEUE_UPDATED has an existing room to update
    alice.emit('PLAY', { roomId: 'schema-queue', positionMs: 0 });
    await new Promise((r) => setTimeout(r, 50));

    const tracks = [
      { id: 'v1', title: 'Track 1' },
      { id: 'v2', title: 'Track 2' },
    ];
    alice.emit('QUEUE_UPDATED', { roomId: 'schema-queue', queue: tracks });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-queue');

    expect(ack.state.queue).toEqual(tracks);
    // currentVideo is derived from the first item in the queue
    expect(ack.state.currentVideo).toEqual({ id: 'v1', title: 'Track 1' });

    alice.disconnect();
    bob.disconnect();
  });

  it('QUEUE_UPDATED before any PLAY/PAUSE bootstraps PAUSED room state', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-queue-bootstrap');

    const tracks = [{ id: 'v1', title: 'Track 1' }];
    alice.emit('QUEUE_UPDATED', { roomId: 'schema-queue-bootstrap', queue: tracks });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-queue-bootstrap');

    expect(ack.state).toMatchObject({
      playbackState: 'PAUSED',
      isPlaying: false,
      positionMs: 0,
      queue: tracks,
      currentVideo: { id: 'v1', title: 'Track 1' },
    });

    alice.disconnect();
    bob.disconnect();
  });

  it('clearing the queue via QUEUE_UPDATED resets currentVideo to null', async () => {
    const alice = await connect();
    await joinRoom(alice, 'schema-queue-clear');

    alice.emit('PLAY', { roomId: 'schema-queue-clear', positionMs: 0 });
    await new Promise((r) => setTimeout(r, 50));

    // First populate the queue
    alice.emit('QUEUE_UPDATED', {
      roomId: 'schema-queue-clear',
      queue: [{ id: 'v1', title: 'Track 1' }],
    });
    await new Promise((r) => setTimeout(r, 50));

    // Then clear it
    alice.emit('QUEUE_UPDATED', { roomId: 'schema-queue-clear', queue: [] });
    await new Promise((r) => setTimeout(r, 50));

    const bob = await connect();
    const ack = await joinRoom(bob, 'schema-queue-clear');

    expect(ack.state.queue).toEqual([]);
    expect(ack.state.currentVideo).toBeNull();

    alice.disconnect();
    bob.disconnect();
  });

  it('state is null for a brand-new room (before any PLAY/PAUSE)', async () => {
    const alice = await connect();
    const ack = await joinRoom(alice, 'schema-new');

    expect(ack.state).toBeNull();

    alice.disconnect();
  });
});
